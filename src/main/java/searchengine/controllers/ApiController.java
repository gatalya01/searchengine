package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.responses.NotOkResponse;
import searchengine.responses.OkResponse;
import searchengine.services.IndexingService;
import searchengine.services.SearchService;
import searchengine.services.StatisticsService;

import java.io.IOException;
import java.net.MalformedURLException;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final SearchService searchService;
    private final StatisticsService statisticsService;
    private final IndexingService indexingService;

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() throws MalformedURLException {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity startIndexing() {
        if (indexingService.isIndexingInProgress()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new NotOkResponse("Индексация уже запущена"));
        } else {
            indexingService.startIndexing();
            return ResponseEntity.status(HttpStatus.OK).body(new OkResponse());
        }
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity stopIndexing() {
        return indexingService.stopIndexingWithResponse();
    }

    @PostMapping("/indexPage")
    public ResponseEntity indexPage(@RequestParam(name = "url") String url) {
        try {
            indexingService.indexPage(url);
            return ResponseEntity.status(HttpStatus.OK).body(new OkResponse());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new NotOkResponse("Данная страница находится за пределами сайтов указанных " +
                            "в конфигурационном файле\""));
        } catch (IOException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new NotOkResponse("\n" +
                            "При индексировании страницы произошла ошибка"));
        }
    }

    @GetMapping("/search")
    public ResponseEntity<Object> search(
            @RequestParam(name = "query", required = false, defaultValue = "") String query,
            @RequestParam(name = "site", required = false, defaultValue = "") String site,
            @RequestParam(name = "offset", required = false, defaultValue = "0") Integer offset,
            @RequestParam(name = "limit", required = false, defaultValue = "10") Integer limit
    ) throws IOException {
        return searchService.search(query, site, offset, limit);
    }
}