package searchengine.dto.statistics;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import searchengine.model.PageEntity;

@Getter
@Setter
@NoArgsConstructor
public class TransferDTO {
    private Integer pageId;
    private PageEntity pageEntity;
    private double absRelevance = 0.0;
    private double relativeRelevance = 0.0;
    private int maxLemmaRank = 0;
}