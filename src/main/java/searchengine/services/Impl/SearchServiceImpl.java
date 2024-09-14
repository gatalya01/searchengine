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

        if (checkIndexStatusNotIndexed(site)) {
            return ResponseEntity.badRequest().body(new NotOkResponse("Индексация сайта для поиска не закончена"));
        }

        try {
            SiteEntity siteTarget = siteRepository.getSitePageByUrl(site);
            Integer countPages = siteTarget != null ? pageRepository.getCountPages(siteTarget.getId()) : pageRepository.getCountPages(null);

            // Инициализируем леммы для поиска и фильтруем частотные леммы
            List<LemmaEntity> lemmasForSearch = lemmaService.getLemmasFromText(query).keySet().stream()
                    .map(lemma -> lemmaRepository.findLemmasByLemmaAndSiteId(lemma, siteTarget != null ? siteTarget.getId() : null))
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList());
            filterFrequentLemmas(lemmasForSearch, countPages);

            if (lemmasForSearch.isEmpty()) {
                return ResponseEntity.ok(new SearchResponse(true, 0, Collections.emptyList()));
            }

            // Сортируем леммы по частоте
            List<LemmaEntity> sortedLemmasToSearch = sortLemmasByFrequency(lemmasForSearch);

            // Ищем страницы по леммам
            Map<Integer, IndexSearchEntity> indexesByLemmas = findPagesByLemmas(sortedLemmasToSearch);

            if (indexesByLemmas.isEmpty()) {
                return ResponseEntity.ok(new SearchResponse(true, 0, Collections.emptyList()));
            }

            // Рассчитываем релевантность страниц
            List<TransferDTO> pagesRelevanceSorted = calculatePageRelevance(indexesByLemmas);

            // Конвертируем результаты в SearchDataResponse без ограничения на количество сниппетов
            List<SearchDataResponse> searchDataResponses = convertToSearchDataResponses(lemmasForSearch, pagesRelevanceSorted);

            int count = searchDataResponses.size();
            List<SearchDataResponse> paginatedResponses = paginateResults(searchDataResponses, offset, limit);
            SearchResponse response = new SearchResponse(true, count, paginatedResponses);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error occurred during search: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new NotOkResponse("Произошла ошибка во время выполнения поиска"));
        }
    }

    // Метод для фильтрации частотных лемм
    private void filterFrequentLemmas(List<LemmaEntity> lemmasForSearch, Integer countPages) {
        lemmasForSearch.removeIf(lemma -> {
            Integer lemmaFrequency = lemmaRepository.findCountPageByLemma(lemma.getLemma(), lemma.getSiteId());
            if (lemmaFrequency == null) return true;
            double frequencyLimit = (double) lemmaFrequency / countPages;
            return frequencyLimit > frequencyLimitProportion;
        });
    }

    // Метод сортировки лемм по частоте
    private List<LemmaEntity> sortLemmasByFrequency(List<LemmaEntity> lemmasForSearch) {
        return lemmasForSearch.stream()
                .sorted(Comparator.comparingInt(LemmaEntity::getFrequency))
                .collect(Collectors.toList());
    }

    // Метод поиска страниц по леммам
    private Map<Integer, IndexSearchEntity> findPagesByLemmas(List<LemmaEntity> sortedLemmasToSearch) {
        Map<Integer, IndexSearchEntity> indexesByLemmas = indexRepository.findIndexesByLemma(sortedLemmasToSearch.get(0).getId())
                .stream()
                .collect(Collectors.toMap(IndexSearchEntity::getPageId, index -> index));

        for (int i = 1; i < sortedLemmasToSearch.size(); i++) {
            List<IndexSearchEntity> indexNextLemma = indexRepository.findIndexesByLemma(sortedLemmasToSearch.get(i).getId());
            List<Integer> pagesToSave = indexNextLemma.stream()
                    .filter(indexNext -> indexesByLemmas.containsKey(indexNext.getPageId()))
                    .map(IndexSearchEntity::getPageId)
                    .collect(Collectors.toList());
            indexesByLemmas.keySet().retainAll(pagesToSave);
        }
        return indexesByLemmas;
    }
    // Метод расчета релевантности страниц
    private List<TransferDTO> calculatePageRelevance(Map<Integer, IndexSearchEntity> indexesByLemmas) {
        Map<Integer, TransferDTO> pagesRelevanceMap = new HashMap<>();

        for (IndexSearchEntity index : indexesByLemmas.values()) {
            int pageId = index.getPageId();
            TransferDTO rankPage = pagesRelevanceMap.computeIfAbsent(pageId, id -> {
                TransferDTO newRankPage = new TransferDTO();
                newRankPage.setPageEntity(index.getPageEntity());
                newRankPage.setPageId(pageId);
                return newRankPage;
            });

            rankPage.setAbsRelevance(rankPage.getAbsRelevance() + index.getLemmaCount());
            if (rankPage.getMaxLemmaRank() < index.getLemmaCount()) {
                rankPage.setMaxLemmaRank(index.getLemmaCount());
            }
        }

        // Вычисляем относительную релевантность
        pagesRelevanceMap.values().forEach(rankPage ->
                rankPage.setRelativeRelevance(rankPage.getAbsRelevance() / rankPage.getMaxLemmaRank()));

        // Сортируем по относительной релевантности
        return pagesRelevanceMap.values().stream()
                .sorted(Comparator.comparingDouble(TransferDTO::getRelativeRelevance).reversed())
                .collect(Collectors.toList());
    }

    // Метод конвертации результатов в SearchDataResponse с неограниченным количеством сниппетов
    private List<SearchDataResponse> convertToSearchDataResponses(List<LemmaEntity> lemmasForSearch, List<TransferDTO> pagesRelevanceSorted) throws IOException {
        List<String> simpleLemmasFromSearch = lemmasForSearch.stream().map(LemmaEntity::getLemma).collect(Collectors.toList());
        List<SearchDataResponse> searchDataResponses = new ArrayList<>();

        for (TransferDTO rank : pagesRelevanceSorted) {
            Document doc = Jsoup.parse(rank.getPageEntity().getContent());
            List<String> sentences = doc.body().getElementsMatchingOwnText("[\\p{IsCyrillic}]").stream().map(Element::text).collect(Collectors.toList());
            StringBuilder highlightedText = new StringBuilder();

            for (String sentence : sentences) {
                StringBuilder textFromElement = new StringBuilder(sentence);
                List<String> words = List.of(sentence.split("[\\s\\p{Punct}]"));
                boolean containsSearchWord = false;

                for (String word : words) {
                    String lemmaFromWord = lemmaService.getLemmaByWord(word.replaceAll("\\p{Punct}", ""));
                    if (simpleLemmasFromSearch.contains(lemmaFromWord)) {
                        markQuery(textFromElement, word, 0);
                        containsSearchWord = true;
                    }
                }

                if (containsSearchWord) {
                    highlightedText.append(textFromElement).append("... ");
                }
            }

            if (highlightedText.length() > 0) {
                SiteEntity sitePage = siteRepository.findById(pageRepository.findById(rank.getPageId()).get().getSiteId()).orElse(null);
                if (sitePage != null) {
                    searchDataResponses.add(new SearchDataResponse(
                            sitePage.getUrl(),
                            sitePage.getName(),
                            rank.getPageEntity().getPath(),
                            doc.title(),
                            highlightedText.toString(),
                            rank.getRelativeRelevance(),
                            (int) rank.getAbsRelevance()));
                }
            }
        }

        return searchDataResponses.stream()
                .sorted(Comparator.comparingDouble(SearchDataResponse::getRelevance).reversed())
                .collect(Collectors.toList());
    }

    // Метод для подсветки слова в тексте
    private void markQuery(StringBuilder textFromElement, String query, int startPosition) {
        // Приведение запроса к нижнему регистру для поиска без учета регистра
        String lowerCaseQuery = query.toLowerCase();

        int start = textFromElement.toString().toLowerCase().indexOf(lowerCaseQuery, startPosition);

        while (start != -1) {
            // Проверка на наличие уже существующих тегов <b>
            if (textFromElement.indexOf("<b>", start - 3) == (start - 3) ||
                    textFromElement.indexOf("<b>", start + lowerCaseQuery.length()) == start + lowerCaseQuery.length()) {
                start = textFromElement.toString().toLowerCase().indexOf(lowerCaseQuery, start + lowerCaseQuery.length());
                continue;
            }

            // Вставка тегов <b> и </b>
            int end = start + lowerCaseQuery.length();
            textFromElement.insert(end, "</b>");
            textFromElement.insert(start, "<b>");

            // Продолжаем поиск с позиции после текущего вхождения
            start = textFromElement.toString().toLowerCase().indexOf(lowerCaseQuery, end + 4);
        }
    }

    // Метод проверки статуса индексации сайта
    private Boolean checkIndexStatusNotIndexed(String site) {
        if (site == null || site.isBlank()) {
            return siteRepository.findAll().stream().anyMatch(s -> !s.getStatus().equals(indexSuccessStatus));
        }
        SiteEntity siteEntity = siteRepository.getSitePageByUrl(site);
        return siteEntity == null || !siteEntity.getStatus().equals(indexSuccessStatus);
    }
    private List<SearchDataResponse> paginateResults(List<SearchDataResponse> searchDataResponses, Integer offset, Integer limit) {
        int startIndex = Math.max(0, offset);
        int endIndex = Math.min(offset + limit, searchDataResponses.size());
        return searchDataResponses.subList(startIndex, endIndex);
    }
}