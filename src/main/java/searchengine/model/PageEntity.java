package searchengine.model;

import com.sun.istack.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;

@Entity
@Table(name = "page", indexes = {@Index(name = "path_index", columnList = "path")})
@NoArgsConstructor
@Setter
@Getter
public class PageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @NotNull
    private int id;
    @NotNull
    @Column(name = "site_id")
    private int siteId;
    @NotNull
    private String path;
    @NotNull
    private int code;
    @NotNull
    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;
    @ManyToOne()
    @JoinColumn(name = "site_id", nullable = false, insertable = false, updatable = false)
    private SiteEntity siteEntity;

    public PageEntity(PageEntity pageEntity) {
        this.id = pageEntity.getId();
        this.siteId = pageEntity.getSiteId();
        this.path = pageEntity.getPath();
        this.code = pageEntity.getCode();
        this.content = pageEntity.getContent();
        this.siteEntity = pageEntity.getSiteEntity();
    }
}