package searchengine.services.Impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
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
import searchengine.services.ApiService;
import searchengine.services.LemmaService;
import searchengine.services.PageIndexerService;
import searchengine.utils.PageFinder;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApiServiceImpl implements ApiService {
    private final PageIndexerService pageIndexerService;
    private final LemmaService lemmaService;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final SitesList sitesToIndexing;
    private final Set<SiteEntity> siteEntityAllFromDB;
    private final ConnectionSettings connection;
    private AtomicBoolean indexingProcessing;

    @Override
    public void startIndexing(AtomicBoolean indexingProcessing) {
        this.indexingProcessing = indexingProcessing;
        try {
            deleteSiteEntityAndPagesInDB();
            addSiteEntityToDB();
            indexAllSiteEntity();
        } catch (RuntimeException | InterruptedException ex) {
            indexingProcessing.set(false);
            log.error("Error: ", ex);
        }
    }

    @Override
    public void refreshEntity(SiteEntity siteDomain, URL url) {
        SiteEntity existSitePage = siteRepository.getSitePageByUrl(siteDomain.getUrl());
        siteDomain.setId(existSitePage.getId());
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
        List<String> urlToIndexing = new ArrayList<>();
        for (Site siteApp : sitesToIndexing.getSites()) {
            urlToIndexing.add(siteApp.getUrl().toString());
        }
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
        indexingProcessing.set(false);
    }
}