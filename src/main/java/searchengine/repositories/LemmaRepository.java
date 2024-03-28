package searchengine.repositories;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import searchengine.model.LemmaEntity;

import java.util.List;

@Repository
public interface LemmaRepository extends JpaRepository<LemmaEntity, Integer> {
    @Query(value = "select * from lemma t where t.lemma = :lemma and t.site_id = :siteId for update", nativeQuery = true)
    LemmaEntity lemmaExist(String lemma, Integer siteId);

    @Query(value = "select count(l) from LemmaEntity l where l.siteId = :siteId")
    Integer findCountRecordBySiteId(Integer siteId);

    @Query(value = "select l.frequency from LemmaEntity l where l.lemma = :lemma and (:siteId is null or l.siteId = :siteId)")
    Integer findCountPageByLemma(String lemma, Integer siteId);

    @Query(value = "select l.id from LemmaEntity l where l.lemma = :lemma")
    Integer findIdLemma(String lemma);

    @Query(value = "select l from LemmaEntity l where l.lemma = :lemma and (:siteId is null or l.siteId = :siteId)")
    List<LemmaEntity> findLemmasByLemmaAndSiteId(String lemma, Integer siteId);
}