package searchengine.services;

import org.springframework.http.ResponseEntity;
import searchengine.model.SiteEntity;

import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

public interface ApiService {

    void startIndexingProcess(AtomicBoolean indexingProcessing);

    void refreshEntity(SiteEntity siteEntity, URL url);

    ResponseEntity startIndexing(AtomicBoolean indexingProcessing);

    ResponseEntity stopIndexing();}