package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import searchengine.model.IndexSearchEntity;

import javax.transaction.Transactional;
import java.util.List;

@Repository
public interface IndexSearchRepository extends JpaRepository<IndexSearchEntity, Integer> {
    @Query(value = "select i from IndexSearchEntity i where i.pageId = :pageId and i.lemmaId = :lemmaId")
    IndexSearchEntity indexSearchExist(@Param("pageId") Integer pageId, @Param("lemmaId") Integer lemmaId);

    @Query(value = "select i from IndexSearchEntity i where i.lemmaId = :lemmaId")
    List<IndexSearchEntity> findIndexesByLemma(Integer lemmaId);

    @Query(value = "select i from IndexSearchEntity i where i.pageId = :pageId")
    List<IndexSearchEntity> findAllByPageId(@Param("pageId") Integer pageId);

    @Modifying
    @Transactional
    @Query(value = "delete from IndexSearchEntity i where i.pageId = :pageId")
    void deleteAllByPageId(@Param("pageId") Integer pageId);}
