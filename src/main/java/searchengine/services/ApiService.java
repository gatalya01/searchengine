package searchengine.services;

import searchengine.model.SiteEntity;

import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

public interface ApiService {

    void refreshEntity(SiteEntity siteEntity, URL url);

    void startIndexing(AtomicBoolean indexingProcessing);}
