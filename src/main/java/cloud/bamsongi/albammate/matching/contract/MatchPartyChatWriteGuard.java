package cloud.bamsongi.albammate.matching.contract;

import java.util.function.Supplier;

/** Party 쓰기 잠금과 현재 접근 판정을 하나의 호출자 트랜잭션으로 제공한다. */
public interface MatchPartyChatWriteGuard {

	<T> T executeWithActiveAccess(long currentUserId, long partyId, Supplier<T> chatOperation);
}
