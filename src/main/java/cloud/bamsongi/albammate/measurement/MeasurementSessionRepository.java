package cloud.bamsongi.albammate.measurement;

import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/** Spring Session 저장소의 save 경계만 감싸며 생성·조회·삭제 위임 의미를 바꾸지 않는다. */
final class MeasurementSessionRepository<S extends Session> implements SessionRepository<S> {

	private final SessionRepository<S> delegate;
	private final AuthNotificationMeasurementRecorder measurementRecorder;

	MeasurementSessionRepository(
		SessionRepository<S> delegate, AuthNotificationMeasurementRecorder measurementRecorder) {
		this.delegate = delegate;
		this.measurementRecorder = measurementRecorder;
	}

	@Override
	public S createSession() {
		return delegate.createSession();
	}

	@Override
	public void save(S session) {
		measurementRecorder.authStage("session-repository-save", () -> delegate.save(session));
	}

	@Override
	public S findById(String id) {
		return delegate.findById(id);
	}

	@Override
	public void deleteById(String id) {
		delegate.deleteById(id);
	}
}
