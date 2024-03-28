package searchengine.services;

import searchengine.model.PageEntity;

public interface PageIndexerService {
    void indexHtml(String html, PageEntity indexingPageEntity);

    void refreshIndex(String html, PageEntity refreshPageEntity);
}
