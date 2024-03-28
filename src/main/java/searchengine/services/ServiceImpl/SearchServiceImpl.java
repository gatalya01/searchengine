package searchengine.services.ServiceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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
        if (checkIndexStatusNotIndexed(site)) {
            return ResponseEntity.badRequest().body(new NotOkResponse("Индексация сайта для поиска не закончена"));
        }
        //
        SiteEntity siteTarget = siteRepository.getSitePageByUrl(site);
        Integer countPages = siteTarget != null ? pageRepository.getCountPages(siteTarget.getId()) : pageRepository.getCountPages(null);

        //Exclusion lemmas by frequent
        List<LemmaEntity> lemmasForSearches = lemmaService.getLemmasFromText(query).keySet().stream().map(
                        it -> lemmaRepository.findLemmasByLemmaAndSiteId(it, siteTarget != null ? siteTarget.getId() : null))
                .flatMap(Collection::stream).collect(Collectors.toList());
        lemmasForSearches.removeIf(e -> {
            Integer lemmaFrequency = lemmaRepository.findCountPageByLemma(e.getLemma(), e.getSiteId());
            if (lemmaFrequency == null) return true;
            log.info("LemmaEntity frequency(" + e + "):" + lemmaFrequency);
            log.info("Frequency limit:" + (double) lemmaFrequency / countPages);
            return ((double) lemmaFrequency / countPages > frequencyLimitProportion);
        });

        if (lemmasForSearches.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        //Sorting lemmas by frequent
        List<LemmaEntity> sortedLemmasToSearches = lemmasForSearches.stream().
                map(l -> new AbstractMap.SimpleEntry<>(l.getFrequency(), l)).
                sorted(Comparator.comparingInt(Map.Entry::getKey)).
                map(Map.Entry::getValue).toList();


        //Search pages by first lemma
        Map<Integer, IndexSearchEntity> indexesByLemmas = indexRepository.findIndexesByLemma(sortedLemmasToSearches.get(0).getId()).stream().collect(Collectors.toMap(IndexSearchEntity::getPageId, index -> index));
        for (int i = 1; i <= sortedLemmasToSearches.size() - 1; i++) {
            List<IndexSearchEntity> indexNextLemma = indexRepository.findIndexesByLemma(sortedLemmasToSearches.get(i).getId());
            List<Integer> pagesToSave = new ArrayList<>();
            for (IndexSearchEntity indexNext : indexNextLemma) {
                if (indexesByLemmas.containsKey(indexNext.getPageId())) {
                    pagesToSave.add(indexNext.getPageId());
                }
            }
            indexesByLemmas.entrySet().removeIf(entry -> !pagesToSave.contains(entry.getKey()));
        }

        //Output if empty result
        if (indexesByLemmas.isEmpty()) {
            return ResponseEntity.ok().body(new SearchResponse(true, 0, Collections.emptyList()));
        }

        //Rank calculation
        Set<TransferDTO> pagesRelevance = new HashSet<>();
        int pageId = indexesByLemmas.values().stream().toList().get(0).getPageId();
        TransferDTO rankPage = new TransferDTO();
        for (IndexSearchEntity index : indexesByLemmas.values()) {
            if (index.getPageId() == pageId) {
                rankPage.setPageEntity(index.getPageEntity());
            } else {
                rankPage.setRelativeRelevance(rankPage.getAbsRelevance() / rankPage.getMaxLemmaRank());
                pagesRelevance.add(rankPage);
                rankPage = new TransferDTO();
                rankPage.setPageEntity(index.getPageEntity());
                pageId = index.getPageId();
            }
            rankPage.setPageId(index.getPageId());
            rankPage.setAbsRelevance(rankPage.getAbsRelevance() + index.getLemmaCount());
            if (rankPage.getMaxLemmaRank() < index.getLemmaCount()) rankPage.setMaxLemmaRank(index.getLemmaCount());
        }
        rankPage.setRelativeRelevance(rankPage.getAbsRelevance() / rankPage.getMaxLemmaRank());
        pagesRelevance.add(rankPage);

        //Sort pages Relevance
        List<TransferDTO> pagesRelevanceSorted = pagesRelevance.stream().sorted(Comparator.comparingDouble(TransferDTO::getRelativeRelevance).reversed()).toList();

        //Converting pages relevance to searchDataResponses
        List<String> simpleLemmasFromSearch = new ArrayList<>(lemmasForSearches.stream().map(LemmaEntity::getLemma).toList());
        List<SearchDataResponse> searchDataResponses = new ArrayList<>();
        for (TransferDTO rank : pagesRelevanceSorted) {
            Document doc = Jsoup.parse(rank.getPageEntity().getContent());
            List<String> sentences = doc.body().getElementsMatchingOwnText("[\\p{IsCyrillic}]").stream().map(Element::text).toList();
            for (String sentence : sentences) {
                StringBuilder textFromElement = new StringBuilder(sentence);
                List<String> words = List.of(sentence.split("[\s:punct]"));
                int searchWords = 0;
                for (String word : words) {
                    String lemmaFromWord = lemmaService.getLemmaByWord(word.replaceAll("\\p{Punct}", ""));
                    if (simpleLemmasFromSearch.contains(lemmaFromWord)) {
                        markWord(textFromElement, word, 0);
                        searchWords += 1;
                    }
                }
                if (searchWords != 0) {
                    SiteEntity siteEntity = siteRepository.findById(pageRepository.findById(rank.getPageId()).get().getSiteId()).get();
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
        List<SearchDataResponse> sortedSearchDataResponse = searchDataResponses.stream().sorted(Comparator.comparingDouble(SearchDataResponse::getRelevance).reversed()).toList();
        List<SearchDataResponse> result = new ArrayList<>();
        for (int i = limit * offset; i <= limit * offset + limit; i++) {
            try {
                result.add(sortedSearchDataResponse.get(i));
            } catch (IndexOutOfBoundsException ex) {
                break;
            }
        }
        result = result.stream().sorted(Comparator.comparingInt(SearchDataResponse::getWordsFound).reversed()).toList();
        return ResponseEntity.ok(result);
    }

    private Boolean checkIndexStatusNotIndexed(String site) {
        if (site == null || site.isBlank()) {
            List<SiteEntity> sites = siteRepository.findAll();
            return sites.stream().anyMatch(s -> !s.getStatus().equals(indexSuccessStatus));
        }
        return !siteRepository.getSitePageByUrl(site).getStatus().equals(indexSuccessStatus);
    }

    private void markWord(StringBuilder textFromElement, String word, int startPosition) {
        int start = textFromElement.indexOf(word, startPosition);
        if (textFromElement.indexOf("<b>", start - 3) == (start - 3)) {
            markWord(textFromElement, word, start + word.length());
            return;
        }
        int end = start + word.length();
        textFromElement.insert(start, "<b>");
        if (end == -1) {
            textFromElement.insert(textFromElement.length(), "</b>");
        } else textFromElement.insert(end + 3, "</b>");
    }
}