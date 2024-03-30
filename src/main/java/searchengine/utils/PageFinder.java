package searchengine.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import searchengine.config.ConnectionSettings;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.LemmaService;
import searchengine.services.PageIndexerService;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;
@Slf4j
@RequiredArgsConstructor
public class PageFinder extends RecursiveAction {
    private final PageIndexerService pageIndexerService;
    private final LemmaService lemmaService;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final AtomicBoolean indexingProcessing;
    private final ConnectionSettings connection;
    private final Set<String> urlSet = new HashSet<>();
    private final String page;
    private final SiteEntity siteDomain;
    private final ConcurrentHashMap<String, PageEntity> resultForkJoinPoolIndexedPages;

    public PageFinder(SiteRepository siteRepository, PageRepository pageRepository, SiteEntity siteDomain, String page, ConcurrentHashMap<String, PageEntity> resultForkJoinPoolIndexedPages, ConnectionSettings connection, LemmaService lemmaService, PageIndexerService pageIndexerService, AtomicBoolean indexingProcessing) {
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.page = page;
        this.resultForkJoinPoolIndexedPages = resultForkJoinPoolIndexedPages;
        this.connection = connection;
        this.indexingProcessing = indexingProcessing;
        this.siteDomain = siteDomain;
        this.lemmaService = lemmaService;
        this.pageIndexerService = pageIndexerService;
    }

    @Override
    protected void compute() {
        if (resultForkJoinPoolIndexedPages.get(page) != null || !indexingProcessing.get()) {
            return;
        }
        PageEntity indexingPageEntity = new PageEntity();
        indexingPageEntity.setPath(page);
        indexingPageEntity.setSiteId(siteDomain.getId());

        try {
            org.jsoup.Connection connect = Jsoup.connect(siteDomain.getUrl() + page).userAgent(connection.getUserAgent()).referrer(connection.getReferer());
            Document doc = connect.timeout(60000).get();

            indexingPageEntity.setContent(doc.head() + String.valueOf(doc.body()));
            if (indexingPageEntity.getContent() == null || indexingPageEntity.getContent().isEmpty() || indexingPageEntity.getContent().isBlank()) {
                throw new IllegalArgumentException("Content of site id:" + indexingPageEntity.getSiteId() + ", page:" + indexingPageEntity.getPath() + " is null or empty");
            }
            Elements pages = doc.getElementsByTag("a");
            for (org.jsoup.nodes.Element element : pages) {
                if (!element.attr("href").isEmpty() && element.attr("href").charAt(0) == '/') {
                    if (resultForkJoinPoolIndexedPages.get(page) != null || !indexingProcessing.get()) {
                        return;
                    } else if (resultForkJoinPoolIndexedPages.get(element.attr("href")) == null) {
                        urlSet.add(element.attr("href"));
                    }
                }
            }
            indexingPageEntity.setCode(doc.connection().response().statusCode());
        } catch (Exception ex) {
            errorHandling(ex, indexingPageEntity);
            resultForkJoinPoolIndexedPages.putIfAbsent(indexingPageEntity.getPath(), indexingPageEntity);
            SiteEntity siteEntity = siteRepository.findById(siteDomain.getId()).orElseThrow();
            siteEntity.setStatusTime(Timestamp.valueOf(LocalDateTime.now()));
            siteRepository.save(siteEntity);
            pageRepository.save(indexingPageEntity);
            log.debug("ERROR INDEXATION, siteId:" + indexingPageEntity.getSiteId() + ", path:" + indexingPageEntity.getPath() + ",code:" + indexingPageEntity.getCode() + ", error:" + ex.getMessage());
            return;
        }
        if (resultForkJoinPoolIndexedPages.get(page) != null || !indexingProcessing.get()) {
            return;
        }
        resultForkJoinPoolIndexedPages.putIfAbsent(indexingPageEntity.getPath(), indexingPageEntity);
        SiteEntity siteEntity = siteRepository.findById(siteDomain.getId()).orElseThrow();
        siteEntity.setStatusTime(Timestamp.valueOf(LocalDateTime.now()));
        siteRepository.save(siteEntity);
        pageRepository.save(indexingPageEntity);
        pageIndexerService.indexHtml(indexingPageEntity.getContent(), indexingPageEntity);
        List<PageFinder> indexingPagesTasks = new ArrayList<>();
        for (String url : urlSet) {
            if (resultForkJoinPoolIndexedPages.get(url) == null && indexingProcessing.get()) {
                PageFinder task = new PageFinder(siteRepository, pageRepository, siteEntity, url, resultForkJoinPoolIndexedPages, connection, lemmaService, pageIndexerService, indexingProcessing);
                task.fork();
                indexingPagesTasks.add(task);
            }
        }
        for (PageFinder task : indexingPagesTasks) {
            if (!indexingProcessing.get()) {
                return;
            }
            task.join();
        }
    }

    public void refreshPage() {
        PageEntity indexingPageEntity = new PageEntity();
        indexingPageEntity.setPath(page);
        indexingPageEntity.setSiteId(siteDomain.getId());

        try {
            org.jsoup.Connection connect = Jsoup.connect(siteDomain.getUrl() + page).userAgent(connection.getUserAgent()).referrer(connection.getReferer());
            Document doc = connect.timeout(60000).get();
            indexingPageEntity.setContent(doc.head() + String.valueOf(doc.body()));
            indexingPageEntity.setCode(doc.connection().response().statusCode());
            if (indexingPageEntity.getContent() == null || indexingPageEntity.getContent().isEmpty() || indexingPageEntity.getContent().isBlank()) {
                throw new IllegalArgumentException("Content of site id:" + indexingPageEntity.getSiteId() + ", page:" + indexingPageEntity.getPath() + " is null or empty");
            }
        } catch (Exception ex) {
            errorHandling(ex, indexingPageEntity);
            SiteEntity siteEntity = siteRepository.findById(siteDomain.getId()).orElseThrow();
            siteEntity.setStatusTime(Timestamp.valueOf(LocalDateTime.now()));
            siteRepository.save(siteEntity);
            pageRepository.save(indexingPageEntity);
            return;
        }
        SiteEntity siteEntity = siteRepository.findById(siteDomain.getId()).orElseThrow();
        siteEntity.setStatusTime(Timestamp.valueOf(LocalDateTime.now()));
        siteRepository.save(siteEntity);

        PageEntity pageEntityToRefresh = pageRepository.findPageBySiteIdAndPath(page, siteEntity.getId());
        if (pageEntityToRefresh != null) {
            pageEntityToRefresh.setCode(indexingPageEntity.getCode());
            pageEntityToRefresh.setContent(indexingPageEntity.getContent());
            pageRepository.save(pageEntityToRefresh);
            pageIndexerService.refreshIndex(indexingPageEntity.getContent(), pageEntityToRefresh);
        } else {
            pageRepository.save(indexingPageEntity);
            pageIndexerService.refreshIndex(indexingPageEntity.getContent(), indexingPageEntity);
        }
    }

    void errorHandling(Exception ex, PageEntity indexingPageEntity) {
        String message = ex.toString();
        int errorCode;
        if (message.contains("UnsupportedMimeTypeException")) {
            errorCode = 415;    // Ссылка на pdf, jpg, png документы
        } else if (message.contains("Status=401")) {
            errorCode = 401;    // На несуществующий домен
        } else if (message.contains("UnknownHostException")) {
            errorCode = 401;
        } else if (message.contains("Status=403")) {
            errorCode = 403;    // Нет доступа, 403 Forbidden
        } else if (message.contains("Status=404")) {
            errorCode = 404;    // // Ссылка на pdf-документ, несущ. страница, проигрыватель
        } else if (message.contains("Status=500")) {
            errorCode = 401;    // Страница авторизации
        } else if (message.contains("ConnectException: Connection refused")) {
            errorCode = 500;    // ERR_CONNECTION_REFUSED, не удаётся открыть страницу
        } else if (message.contains("SSLHandshakeException")) {
            errorCode = 525;
        } else if (message.contains("Status=503")) {
            errorCode = 503; // Сервер временно не имеет возможности обрабатывать запросы по техническим причинам (обслуживание, перегрузка и прочее).
        } else {
            errorCode = -1;
        }
        indexingPageEntity.setCode(errorCode);
    }
}