package searchengine.services.Impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import searchengine.config.ConnectionSettings;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.model.PageEntity;

import searchengine.model.SiteEntity;
import searchengine.model.SiteStatus;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.responses.NotOkResponse;
import searchengine.responses.OkResponse;
import searchengine.services.IndexingService;
import searchengine.services.LemmaService;
import searchengine.services.PageIndexerService;
import searchengine.utils.PageFinder;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingServiceImpl implements IndexingService {
    private final PageIndexerService pageIndexerService;
    private final LemmaService lemmaService;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final SitesList sitesToIndexing;
    private final Set<SiteEntity> siteEntityAllFromDB = new HashSet<>();
    private final ConnectionSettings connection;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean indexingProcessing = new AtomicBoolean(false);

    @Override
    public void startIndexing() {
        executor.submit(() -> {
            indexingProcessing.set(true);
            try {
                deleteSiteEntityAndPagesInDB();
                addSiteEntityToDB();
                indexAllSiteEntity();
            } catch (RuntimeException | InterruptedException ex) {
                log.error("Error: ", ex);
            } finally {
                indexingProcessing.set(false);
            }
        });
    }

    @Override
    public void stopIndexing() {
        indexingProcessing.set(false);
    }

    @Override
    public ResponseEntity stopIndexingWithResponse() {
        if (!isIndexingInProgress()) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                    .body(new NotOkResponse("Индексация не запущена"));
        } else {
            stopIndexing();
            return ResponseEntity.status(HttpStatus.OK).body(new OkResponse());
        }
    }

    @Override
    public void indexPage(String url) throws IOException {
        URL refUrl = new URL(url);
        SiteEntity siteEntity = new SiteEntity();
        try {
            sitesToIndexing.getSites().stream()
                    .filter(site -> refUrl.getHost().equals(site.getUrl().getHost()))
                    .findFirst()
                    .map(site -> {
                        siteEntity.setName(site.getName());
                        siteEntity.setUrl(site.getUrl().toString());
                        return siteEntity;
                    }).orElseThrow(() -> new IllegalArgumentException("Данная страница находится за пределами сайтов " +
                            "указанных в конфигурационном файле"));
            refreshEntity(siteEntity, refUrl);
        } catch (IllegalArgumentException ex) {
            log.error("Данная страница находится за пределами сайтов указанных в конфигурационном файле: {}", url, ex);
            throw ex;
        } catch (Exception ex) {
            log.error("При индексировании страницы произошла ошибка: {}", url, ex);
            throw new IOException("При индексировании страницы произошла ошибка", ex);
        }
    }

    @Override
    public boolean isIndexingInProgress() {
        return indexingProcessing.get();
    }

    private void deleteSiteEntityAndPagesInDB() {
        List<SiteEntity> sitesFromDB = siteRepository.findAll();
        for (SiteEntity siteEntityDB : sitesFromDB) {
            for (Site siteApp : sitesToIndexing.getSites()) {
                if (siteEntityDB.getUrl().equals(siteApp.getUrl().toString())) {
                    siteRepository.deleteById(siteEntityDB.getId());
                }
            }
        }
    }

    private void addSiteEntityToDB() {
        for (Site siteApp : sitesToIndexing.getSites()) {
            SiteEntity siteEntityDAO = new SiteEntity();
            siteEntityDAO.setStatus(SiteStatus.INDEXING);
            siteEntityDAO.setName(siteApp.getName());
            siteEntityDAO.setUrl(siteApp.getUrl().toString());
            siteRepository.save(siteEntityDAO);
        }
    }

    private void indexAllSiteEntity() throws InterruptedException {
        siteEntityAllFromDB.addAll(siteRepository.findAll());
        List<String> urlToIndexing = sitesToIndexing.getSites().stream()
                .map(site -> site.getUrl().toString())
                .collect(Collectors.toList());
        siteEntityAllFromDB.removeIf(sitePage -> !urlToIndexing.contains(sitePage.getUrl()));

        List<Thread> indexingThreadList = new ArrayList<>();
        for (SiteEntity siteDomain : siteEntityAllFromDB) {
            Runnable indexSite = () -> {
                ConcurrentHashMap<String, PageEntity> resultForkJoinPageIndexer = new ConcurrentHashMap<>();
                try {
                    log.info("Запущена индексация {}", siteDomain.getUrl());
                    new ForkJoinPool().invoke(new PageFinder(siteRepository, pageRepository, siteDomain, "", resultForkJoinPageIndexer, connection, lemmaService, pageIndexerService, indexingProcessing));
                } catch (SecurityException ex) {
                    SiteEntity siteEntity = siteRepository.findById(siteDomain.getId()).orElseThrow();
                    siteEntity.setStatus(SiteStatus.FAILED);
                    siteEntity.setLastError(ex.getMessage());
                    siteRepository.save(siteEntity);
                }
                if (!indexingProcessing.get()) {
                    log.warn("Indexing stopped by user, site: {}", siteDomain.getUrl());
                    SiteEntity siteEntity = siteRepository.findById(siteDomain.getId()).orElseThrow();
                    siteEntity.setStatus(SiteStatus.FAILED);
                    siteEntity.setLastError("Indexing stopped by user");
                    siteRepository.save(siteEntity);
                } else {
                    log.info("Проиндексирован сайт: {}", siteDomain.getUrl());
                    SiteEntity siteEntity = siteRepository.findById(siteDomain.getId()).orElseThrow();
                    siteEntity.setStatus(SiteStatus.INDEXED);
                    siteRepository.save(siteEntity);
                }
            };
            Thread thread = new Thread(indexSite);
            indexingThreadList.add(thread);
            thread.start();
        }
        for (Thread thread : indexingThreadList) {
            thread.join();
        }
    }

    @Override
    public void refreshEntity(SiteEntity siteDomain, URL url) {
        SiteEntity existSitePage = siteRepository.getSitePageByUrl(siteDomain.getUrl());
        if (existSitePage != null) {
            siteDomain.setId(existSitePage.getId());
        } else {
            // Create a new entry in the database if it does not exist
            siteDomain.setStatus(SiteStatus.INDEXING);
            siteRepository.save(siteDomain);
        }

        ConcurrentHashMap<String, PageEntity> resultForkJoinPageIndexer = new ConcurrentHashMap<>();
        try {
            log.info("Запущена переиндексация страницы: {}", url.toString());
            PageFinder pageFinder = new PageFinder(siteRepository, pageRepository, siteDomain, url.getPath(), resultForkJoinPageIndexer, connection, lemmaService, pageIndexerService, indexingProcessing);
            pageFinder.refreshPage();
        } catch (SecurityException ex) {
            SiteEntity siteEntity = siteRepository.findById(siteDomain.getId()).orElseThrow();
            siteEntity.setStatus(SiteStatus.FAILED);
            siteEntity.setLastError(ex.getMessage());
            siteRepository.save(siteEntity);
        }
        log.info("Проиндексирован сайт: {}", siteDomain.getName());
        SiteEntity siteEntity = siteRepository.findById(siteDomain.getId()).orElseThrow();
        siteEntity.setStatus(SiteStatus.INDEXED);
        siteRepository.save(siteEntity);
    }
}