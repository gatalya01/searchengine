package searchengine.services;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.SiteEntity;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.StatisticsService;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;


@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {
    private final SitesList sites;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;

    @Override
    public StatisticsResponse getStatistics() throws MalformedURLException {
        List<SiteEntity> siteEntities = siteRepository.findAll();
        if (siteEntities.isEmpty()) {
            return getStartStatistics();
        }
        TotalStatistics total = new TotalStatistics();
        total.setSites(sites.getSites().size());
        total.setIndexing(true);

        List<DetailedStatisticsItem> detailed = new ArrayList<>();
        List<SiteEntity> sites = siteRepository.findAll();
        for (SiteEntity siteEntity : sites) {
            Site site = new Site();
            site.setName(siteEntity.getName());
            site.setUrl(String.valueOf(new URL(siteEntity.getUrl())));
            DetailedStatisticsItem item = new DetailedStatisticsItem();
            item.setName(site.getName());
            item.setUrl(site.getUrl().toString());
            int pages = pageRepository.findCountRecordBySiteId(siteEntity.getId());
            int lemmas = lemmaRepository.findCountRecordBySiteId(siteEntity.getId());
            item.setPages(pages);
            item.setLemmas(lemmas);
            item.setStatus(String.valueOf(siteEntity.getStatus()));
            item.setError(siteEntity.getLastError());
            item.setStatusTime(siteEntity.getStatusTime().getTime());
            total.setPages(total.getPages() + pages);
            total.setLemmas(total.getLemmas() + lemmas);
            detailed.add(item);
        }
        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        data.setDetailed(detailed);
        response.setStatistics(data);
        response.setResult(true);
        return response;
    }

    StatisticsResponse getStartStatistics() {
        TotalStatistics total = new TotalStatistics();
        total.setSites(sites.getSites().size());
        total.setIndexing(false);
        List<DetailedStatisticsItem> detailed = new ArrayList<>();
        for (Site site : sites.getSites()) {
            DetailedStatisticsItem item = new DetailedStatisticsItem();
            item.setName(site.getName());
            item.setUrl(String.valueOf(site.getUrl()));
            item.setPages(0);
            item.setLemmas(0);
            item.setStatus(null);
            item.setError(null);
            item.setStatus("WAIT");
            item.setStatusTime(Instant.now().toEpochMilli());
            detailed.add(item);
        }
        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        data.setDetailed(detailed);
        response.setStatistics(data);
        response.setResult(true);
        return response;
    }
}