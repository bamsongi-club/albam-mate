package cloud.bamsongi.albammate.infra.ai;

import cloud.bamsongi.albammate.assistant.contract.AssistantCostWarningEvent;

interface AiCostWarningEventSink {

	void record(AssistantCostWarningEvent event);
}
