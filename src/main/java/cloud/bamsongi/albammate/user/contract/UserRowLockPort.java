package cloud.bamsongi.albammate.user.contract;

import java.util.Collection;
import java.util.Set;

/** MATCH 명령이 사용자 행을 결정적인 순서로 잠글 때 사용하는 공개 계약이다. */
public interface UserRowLockPort {

	/**
	 * 입력 사용자 ID 중 실제 존재하는 행을 오름차순으로 잠근다.
	 *
	 * <p>호출자 트랜잭션에 참여하며 사용자 Entity는 반환하지 않는다.
	 *
	 * @param userIds 잠글 사용자 ID
	 * @return 실제로 잠근 사용자 ID
	 */
	Set<Long> lockExistingUsersInAscendingOrder(Collection<Long> userIds);
}
