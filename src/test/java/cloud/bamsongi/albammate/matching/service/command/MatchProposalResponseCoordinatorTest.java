package cloud.bamsongi.albammate.matching.service.command;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import cloud.bamsongi.albammate.matching.MatchProposalResponseAction;
import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;
import cloud.bamsongi.albammate.matching.service.query.MatchCurrentStateQueryCoordinator;

class MatchProposalResponseCoordinatorTest {

	@Test
	void 최초_유효_응답의_Command_commit_뒤_현재_상태를_조합하고_관측을_완료한다() {
		MatchProposalResponseService responseService = mock(MatchProposalResponseService.class);
		MatchCurrentStateQueryCoordinator currentStateQueryCoordinator = mock(MatchCurrentStateQueryCoordinator.class);
		MatchProposalResponseCompletionProbe completionProbe = mock(MatchProposalResponseCompletionProbe.class);
		CurrentMatchStateResponse currentState = CurrentMatchStateResponse.empty(Instant.EPOCH);
		MatchProposalResponseCoordinator coordinator = new MatchProposalResponseCoordinator(
			responseService, currentStateQueryCoordinator, completionProbe);
		when(currentStateQueryCoordinator.read(1L)).thenReturn(currentState);

		CurrentMatchStateResponse result = coordinator.respond(
			1L, 2L, MatchProposalResponseAction.ACCEPT, "response-key");

		assertSame(currentState, result);
		InOrder inOrder = inOrder(responseService, currentStateQueryCoordinator, completionProbe);
		inOrder.verify(responseService).respond(1L, 2L, MatchProposalResponseAction.ACCEPT, "response-key");
		inOrder.verify(currentStateQueryCoordinator).read(1L);
		inOrder.verify(completionProbe).complete();
	}

	@Test
	void 현재_상태_DTO_조합이_실패하면_성공_완료_대신_bounded_실패_단계만_기록하고_원래_예외를_유지한다() {
		MatchProposalResponseService responseService = mock(MatchProposalResponseService.class);
		MatchCurrentStateQueryCoordinator currentStateQueryCoordinator = mock(MatchCurrentStateQueryCoordinator.class);
		MatchProposalResponseCompletionProbe completionProbe = mock(MatchProposalResponseCompletionProbe.class);
		MatchProposalResponseCoordinator coordinator = new MatchProposalResponseCoordinator(
			responseService, currentStateQueryCoordinator, completionProbe);
		IllegalStateException expected = new IllegalStateException("current-state assembly failed");
		doThrow(expected).when(currentStateQueryCoordinator).read(1L);

		IllegalStateException actual = assertThrows(IllegalStateException.class, () -> coordinator.respond(
			1L, 2L, MatchProposalResponseAction.ACCEPT, "response-key"));

		assertSame(expected, actual);
		verify(completionProbe).fail(MatchProposalResponseCompletionProbe.FailureStage.CURRENT_STATE_ASSEMBLY);
		verify(completionProbe, never()).complete();
	}

	@Test
	void 관측_기록_실패는_성공_응답과_원래_DTO_조합_예외를_바꾸지_않는다() {
		MatchProposalResponseService responseService = mock(MatchProposalResponseService.class);
		MatchCurrentStateQueryCoordinator currentStateQueryCoordinator = mock(MatchCurrentStateQueryCoordinator.class);
		MatchProposalResponseCompletionProbe completionProbe = mock(MatchProposalResponseCompletionProbe.class);
		CurrentMatchStateResponse currentState = CurrentMatchStateResponse.empty(Instant.EPOCH);
		MatchProposalResponseCoordinator coordinator = new MatchProposalResponseCoordinator(
			responseService, currentStateQueryCoordinator, completionProbe);
		when(currentStateQueryCoordinator.read(1L)).thenReturn(currentState);
		doThrow(new IllegalStateException("completion recording failed")).when(completionProbe).complete();

		assertSame(currentState, coordinator.respond(1L, 2L, MatchProposalResponseAction.ACCEPT, "response-key"));

		IllegalStateException expected = new IllegalStateException("current-state assembly failed");
		doThrow(expected).when(currentStateQueryCoordinator).read(1L);
		doThrow(new IllegalStateException("failure recording failed")).when(completionProbe)
			.fail(MatchProposalResponseCompletionProbe.FailureStage.CURRENT_STATE_ASSEMBLY);

		assertSame(expected, assertThrows(IllegalStateException.class, () -> coordinator.respond(
			1L, 2L, MatchProposalResponseAction.ACCEPT, "response-key")));
	}
}
