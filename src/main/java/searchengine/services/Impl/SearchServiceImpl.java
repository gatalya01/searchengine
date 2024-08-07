package searchengine.services.Impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import searchengine.dto.statistics.TransferDTO;
import searchengine.model.IndexSearchEntity;
import searchengine.model.LemmaEntity;
import searchengine.model.SiteEntity;
import searchengine.model.SiteStatus;
import searchengine.repositories.IndexSearchRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.responses.NotOkResponse;
import searchengine.responses.SearchDataResponse;
import searchengine.responses.SearchResponse;
import searchengine.services.LemmaService;
import searchengine.services.SearchService;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchServiceImpl implements SearchService {
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexSearchRepository indexRepository;
    private final LemmaService lemmaService;
    private final SiteStatus indexSuccessStatus = SiteStatus.INDEXED;
    private final double frequencyLimitProportion = 100;

    @Override
    public ResponseEntity<Object> search(String query, String site, Integer offset, Integer limit) throws IOException {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(new NotOkResponse("Задан пустой поисковый запрос"));
        }
        try {
            if (checkIndexStatusNotIndexed(site)) {
                return ResponseEntity.badRequest().body(new NotOkResponse("Индексация сайта для поиска не закончена"));
            }

            List<SearchDataResponse> searchDataResponses = processSearch(query, site);
            int count = searchDataResponses.size();
            List<SearchDataResponse> paginatedResponses = paginateResults(searchDataResponses, offset, limit);
            SearchResponse response = new SearchResponse(true, count, paginatedResponses);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error occurred during search: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new NotOkResponse("Произошла ошибка во время выполнения поиска"));
        }
    }

    private List<SearchDataResponse> processSearch(String query, String site) throws IOException {
        SiteEntity siteTarget = siteRepository.getSitePageByUrl(site);
        Integer countPages = siteTarget != null ? pageRepository.getCountPages(siteTarget.getId()) : pageRepository.getCountPages(null);

        List<LemmaEntity> lemmasForSearches = lemmaService.getLemmasFromText(query).keySet().stream()
                .map(it -> lemmaRepository.findLemmasByLemmaAndSiteId(it, siteTarget != null ? siteTarget.getId() : null))
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        lemmasForSearches = filterLemmas(lemmasForSearches, countPages);

        if (lemmasForSearches.isEmpty()) {
            return Collections.emptyList();
        }

        List<LemmaEntity> sortedLemmasToSearches = sortLemmas(lemmasForSearches);
        Map<Integer, IndexSearchEntity> indexesByLemmas = findIndexesByLemmas(sortedLemmasToSearches);

        if (indexesByLemmas.isEmpty()) {
            return Collections.emptyList();
        }

        List<TransferDTO> pagesRelevance = calculatePageRelevance(indexesByLemmas);
        return prepareSearchDataResponses(lemmasForSearches, pagesRelevance);
    }

    private List<LemmaEntity> filterLemmas(List<LemmaEntity> lemmasForSearches, Integer countPages) {
        return lemmasForSearches.stream()
                .filter(e -> {
                    Integer lemmaFrequency = lemmaRepository.findCountPageByLemma(e.getLemma(), e.getSiteId());
                    return lemmaFrequency != null && ((double) lemmaFrequency / countPages <= frequencyLimitProportion);
                })
                .collect(Collectors.toList());
    }

    private List<LemmaEntity> sortLemmas(List<LemmaEntity> lemmasForSearches) {
        return lemmasForSearches.stream()
                .map(l -> new AbstractMap.SimpleEntry<>(l.getFrequency(), l))
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .toList();
    }

    private Map<Integer, IndexSearchEntity> findIndexesByLemmas(List<LemmaEntity> sortedLemmasToSearches) {
        Map<Integer, IndexSearchEntity> indexesByLemmas = indexRepository.findIndexesByLemma(sortedLemmasToSearches.get(0).getId())
                .stream()
                .collect(Collectors.toMap(IndexSearchEntity::getPageId, index -> index));

        for (int i = 1; i < sortedLemmasToSearches.size(); i++) {
            List<IndexSearchEntity> indexNextLemma = indexRepository.findIndexesByLemma(sortedLemmasToSearches.get(i).getId());
            List<Integer> pagesToSave = indexNextLemma.stream()
                    .filter(indexNext -> indexesByLemmas.containsKey(indexNext.getPageId()))
                    .map(IndexSearchEntity::getPageId)
                    .collect(Collectors.toList());
            indexesByLemmas.keySet().retainAll(pagesToSave);
        }
        return indexesByLemmas;
    }

    private List<TransferDTO> calculatePageRelevance(Map<Integer, IndexSearchEntity> indexesByLemmas) {
        Set<TransferDTO> pagesRelevance = new HashSet<>();
        TransferDTO rankPage = new TransferDTO();
        int pageId = -1;

        for (IndexSearchEntity index : indexesByLemmas.values()) {
            if (index.getPageId() != pageId) {
                if (pageId != -1) {
                    rankPage.setRelativeRelevance(rankPage.getAbsRelevance() / rankPage.getMaxLemmaRank());
                    pagesRelevance.add(rankPage);
                }
                rankPage = new TransferDTO();
                rankPage.setPageEntity(index.getPageEntity());
                rankPage.setPageId(index.getPageId());
                pageId = index.getPageId();
            }
            rankPage.setAbsRelevance(rankPage.getAbsRelevance() + index.getLemmaCount());
            if (rankPage.getMaxLemmaRank() < index.getLemmaCount()) {
                rankPage.setMaxLemmaRank(index.getLemmaCount());
            }
        }

        if (pageId != -1) {
            rankPage.setRelativeRelevance(rankPage.getAbsRelevance() / rankPage.getMaxLemmaRank());
            pagesRelevance.add(rankPage);
        }

        return pagesRelevance.stream()
                .sorted(Comparator.comparingDouble(TransferDTO::getRelativeRelevance).reversed())
                .toList();
    }

    private List<SearchDataResponse> prepareSearchDataResponses(List<LemmaEntity> lemmasForSearches, List<TransferDTO> pagesRelevance) {
        List<SearchDataResponse> searchDataResponses = new ArrayList<>();
        List<String> simpleLemmasFromSearch = lemmasForSearches.stream()
                .map(LemmaEntity::getLemma)
                .toList();

        for (TransferDTO rank : pagesRelevance) {
            Document doc = Jsoup.parse(rank.getPageEntity().getContent());
            List<String> sentences = doc.body().getElementsMatchingOwnText(".*\\p{IsCyrillic}.*|.*\\p{IsLatin}.*")
                    .stream()
                    .map(Element::text)
                    .toList();

            for (String sentence : sentences) {
                StringBuilder textFromElement = new StringBuilder(sentence);
                List<String> words = List.of(sentence.split("\\s+"));
                int searchWords = 0;

                for (String word : words) {
                    String lemmaFromWord = lemmaService.getLemmaByWord(word.replaceAll("\\p{Punct}", ""));
                    if (simpleLemmasFromSearch.contains(lemmaFromWord)) {
                        markWord(textFromElement, word, 0);
                        searchWords++;
                    }
                }

                if (searchWords > 0) {
                    SiteEntity siteEntity = siteRepository.findById(pageRepository.findById(rank.getPageId()).get().getSiteId()).orElse(null);
                    if (siteEntity != null) {
                        searchDataResponses.add(new SearchDataResponse(
                                siteEntity.getUrl(),
                                siteEntity.getName(),
                                rank.getPageEntity().getPath(),
                                doc.title(),
                                textFromElement.toString(),
                                rank.getRelativeRelevance(),
                                searchWords
                        ));
                    }
                }
            }
        }
        return searchDataResponses;
    }

    private void markWord(StringBuilder textFromElement, String word, int startPosition) {
        int start = textFromElement.indexOf(word, startPosition);
        if (start == -1) return;
        if (textFromElement.indexOf("<b>", start - 3) == (start - 3)) {
            markWord(textFromElement, word, start + word.length());
            return;
        }
        int end = start + word.length();
        textFromElement.insert(start, "<b>");
        textFromElement.insert(end + 3, "</b>");
    }

    private Boolean checkIndexStatusNotIndexed(String site) {
        if (site == null || site.isBlank()) {
            return siteRepository.findAll().stream().anyMatch(s -> !s.getStatus().equals(indexSuccessStatus));
        }
        return !siteRepository.getSitePageByUrl(site).getStatus().equals(indexSuccessStatus);
    }

    private List<SearchDataResponse> paginateResults(List<SearchDataResponse> searchDataResponses, Integer offset, Integer limit) {
        int startIndex = Math.max(0, offset);
        int endIndex = Math.min(offset + limit, searchDataResponses.size());
        return searchDataResponses.subList(startIndex, endIndex);
    }
}