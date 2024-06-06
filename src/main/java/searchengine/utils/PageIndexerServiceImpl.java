package searchengine.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import searchengine.model.IndexSearchEntity;
import searchengine.model.LemmaEntity;
import searchengine.model.PageEntity;
import searchengine.repositories.IndexSearchRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.services.LemmaService;
import searchengine.services.PageIndexerService;

import javax.transaction.Transactional;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
@Component
@Slf4j
@Service
@RequiredArgsConstructor
public class PageIndexerServiceImpl implements PageIndexerService {
    private final LemmaService lemmaService;
    private final LemmaRepository lemmaRepository;
    private final IndexSearchRepository indexSearchRepository;

    @Override
    public void indexHtml(String html, PageEntity indexingPageEntity) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Integer> lemmas = lemmaService.getLemmasFromText(html);
            lemmas.entrySet().parallelStream().forEach(entry -> saveLemma(entry.getKey(), entry.getValue(), indexingPageEntity));
            log.debug("Индексация страницы " + (System.currentTimeMillis() - start) + " lemmas:" + lemmas.size());
        } catch (IOException e) {
            log.error(String.valueOf(e));
            throw new RuntimeException(e);
        }
    }

    @Override
    public void refreshIndex(String html, PageEntity refreshPageEntity) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Integer> lemmas = lemmaService.getLemmasFromText(html);
            refreshLemma(refreshPageEntity);
            indexSearchRepository.deleteAllByPageId(refreshPageEntity.getId());
            lemmas.entrySet().parallelStream().forEach(entry -> saveLemma(entry.getKey(), entry.getValue(), refreshPageEntity));
            log.debug("Обновление индекса страницы " + (System.currentTimeMillis() - start) + " lemmas:" + lemmas.size());
        } catch (IOException e) {
            log.error(String.valueOf(e));
            throw new RuntimeException(e);
        }
    }

    @Transactional
    private void refreshLemma(PageEntity refreshPageEntity) {
        List<IndexSearchEntity> indexes = indexSearchRepository.findAllByPageId(refreshPageEntity.getId());
        indexes.forEach(idx -> {
            Optional<LemmaEntity> lemmaToRefresh = lemmaRepository.findById(idx.getLemmaId());
            lemmaToRefresh.ifPresent(lemma -> {
                lemma.setFrequency(lemma.getFrequency() - idx.getLemmaCount());
                lemmaRepository.saveAndFlush(lemma);
            });
        });
    }

    @Transactional
    private void saveLemma(String k, Integer v, PageEntity indexingPageEntity) {
        LemmaEntity existLemmaInDBEntity = lemmaRepository.lemmaExist(k, indexingPageEntity.getSiteId());
        if (existLemmaInDBEntity != null) {
            existLemmaInDBEntity.setFrequency(existLemmaInDBEntity.getFrequency() + v);
            lemmaRepository.saveAndFlush(existLemmaInDBEntity);
            createIndex(indexingPageEntity, existLemmaInDBEntity, v);
        } else {
            try {
                LemmaEntity newLemmaToDBEntity = new LemmaEntity();
                newLemmaToDBEntity.setSiteId(indexingPageEntity.getSiteId());
                newLemmaToDBEntity.setLemma(k);
                newLemmaToDBEntity.setFrequency(v);
                newLemmaToDBEntity.setSiteEntity(indexingPageEntity.getSiteEntity());
                lemmaRepository.saveAndFlush(newLemmaToDBEntity);
                createIndex(indexingPageEntity, newLemmaToDBEntity, v);
            } catch (DataIntegrityViolationException ex) {
                log.debug("Ошибка при сохранении леммы, такая лемма уже существует. Вызов повторного сохранения");
                saveLemma(k, v, indexingPageEntity);
            }
        }
    }

    private void createIndex(PageEntity indexingPageEntity, LemmaEntity lemmaEntityInDB, Integer rank) {
        IndexSearchEntity indexSearchEntityExist = indexSearchRepository.indexSearchExist(indexingPageEntity.getId(), lemmaEntityInDB.getId());
        if (indexSearchEntityExist != null) {
            indexSearchEntityExist.setLemmaCount(indexSearchEntityExist.getLemmaCount() + rank);
            indexSearchRepository.save(indexSearchEntityExist);
        } else {
            IndexSearchEntity index = new IndexSearchEntity();
            index.setPageId(indexingPageEntity.getId());
            index.setLemmaId(lemmaEntityInDB.getId());
            index.setLemmaCount(rank);
            index.setLemmaEntity(lemmaEntityInDB);
            index.setPageEntity(indexingPageEntity);
            indexSearchRepository.save(index);
        }
    }
}