package searchengine.services;

import org.springframework.http.ResponseEntity;
import searchengine.model.SiteEntity;

import java.io.IOException;
import java.net.URL;

public interface IndexingService {
    ResponseEntity stopIndexingWithResponse();
    void stopIndexing();
    void startIndexing();
    boolean isIndexingInProgress();
    void indexPage(String url) throws IOException;
    void refreshEntity(SiteEntity siteDomain, URL url);}
