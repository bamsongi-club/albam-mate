package cloud.bamsongi.albammate.infra.ai;

import cloud.bamsongi.albammate.assistant.contract.AssistantUsageEvent;

interface AssistantUsageEventSink {

	void record(AssistantUsageEvent event);
}
