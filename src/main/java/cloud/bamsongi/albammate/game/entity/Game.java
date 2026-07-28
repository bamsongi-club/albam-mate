package cloud.bamsongi.albammate.game.entity;

import cloud.bamsongi.albammate.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "games")
public class Game extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "bgg_id", nullable = false, unique = true)
    private Long bggId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "english_name", nullable = false, length = 255)
    private String englishName;

    @Column(name = "alias", length = 255)
    private String alias;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "supported_player_count", nullable = false, length = 50)
    private String supportedPlayerCount;

    @Column(name = "tag", nullable = false, length = 30)
    private String tag;

    @Column(name = "estimated_play_time", nullable = false, length = 50)
    private String estimatedPlayTime;

    @Column(name = "complexity", precision = 3, scale = 2)
    private BigDecimal complexity;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "detail_description", nullable = false, columnDefinition = "TEXT")
    private String detailDescription;

    public Game(
            Long bggId,
            String name,
            String englishName,
            String supportedPlayerCount,
            String tag,
            String estimatedPlayTime,
            String description,
            String detailDescription) {
        this.bggId = bggId;
        this.name = name;
        this.englishName = englishName;
        this.supportedPlayerCount = supportedPlayerCount;
        this.tag = tag;
        this.estimatedPlayTime = estimatedPlayTime;
        this.description = description;
        this.detailDescription = detailDescription;
    }

    public Long getId() {
        return id;
    }

    public Long getBggId() {
        return bggId;
    }

    public String getName() {
        return name;
    }
}
