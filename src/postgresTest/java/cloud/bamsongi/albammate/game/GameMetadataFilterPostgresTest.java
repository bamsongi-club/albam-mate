package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.service.GameListSearchCriteria;

@Testcontainers
@SpringBootTest
class GameMetadataFilterPostgresTest {
	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");

	@Autowired
	GameRepository games;
	@Autowired
	org.springframework.jdbc.core.JdbcTemplate jdbc;

	@Test
	void 추천과_베스트_OR는_카테고리_테마_가능인원과_AND로_내용과_total에같이적용된다() {
		game(1, "A");
		game(2, "B");
		jdbc.update(
			"insert into game_categories(code,name_ko,name_en,bgg_subdomain,display_order,created_at,updated_at) values('STRATEGY','전략','Strategy','strategygames',1,current_timestamp,current_timestamp)");
		jdbc.update(
			"insert into game_themes(bgg_theme_id,code,name_ko,name_en,created_at,updated_at) values(1,'FANTASY','판타지','Fantasy',current_timestamp,current_timestamp)");
		jdbc.update(
			"insert into game_mechanisms(bgg_mechanism_id,code,name_ko,name_en,is_public,source_reference,reviewed_by,reviewed_at,created_at,updated_at) values(1,'DRAFTING','드래프팅','Drafting',true,'test','test',current_timestamp,current_timestamp,current_timestamp)");
		jdbc.update(
			"insert into game_category_relations select g.id,c.id from games g,game_categories c where g.bgg_id=1");
		jdbc.update("insert into game_theme_relations select g.id,t.id from games g,game_themes t where g.bgg_id=1");
		jdbc.update(
			"insert into game_mechanism_relations select g.id,m.id from games g,game_mechanisms m where g.bgg_id=1");
		jdbc.update("insert into game_player_preferences select id,3,true,false from games where bgg_id=1");
		jdbc.update("insert into game_player_preferences select id,4,true,true from games where bgg_id=1");

		GameListRequest request = new GameListRequest();
		request.setCategory(List.of("STRATEGY"));
		request.setTheme(List.of("FANTASY"));
		request.setMechanism(List.of("DRAFTING"));
		request.setPlayerCount(2);
		request.setRecommendedPlayerCount(List.of(3, 5));
		request.setBestPlayerCount(List.of(4, 5));
		var page = games.findAll(GameListSearchCriteria.from(request).toSpecification(), PageRequest.of(0, 10));

		assertEquals(1, page.getTotalElements());
		assertEquals(List.of(1L), page.getContent().stream().map(game -> game.getBggId()).toList());
	}

	private void game(long bggId, String name) {
		jdbc.update(
			"insert into games(bgg_id,name,english_name,supported_player_count,tag,estimated_play_time,min_players,max_players,description,detail_description,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
			bggId, name, name, "2~4", "tag", "30", 2, 4, "d", "d", java.sql.Timestamp.from(Instant.EPOCH),
			java.sql.Timestamp.from(Instant.EPOCH));
	}
}
