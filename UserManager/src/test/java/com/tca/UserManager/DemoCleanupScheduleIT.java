package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.TestPropertySource;

import com.tca.UserManager.support.IntegrationTestBase;

/** Positive control for DemoContentCleanupIT: setting DEMO_CLEANUP_CRON registers the job. */
@TestPropertySource(properties = "DEMO_CLEANUP_CRON=0 0 * * * *")
class DemoCleanupScheduleIT extends IntegrationTestBase {

	@Autowired List<ScheduledTaskHolder> scheduledTaskHolders;

	@Test
	void settingTheCronRegistersTheCleanupJob() {
		assertThat(scheduledTaskHolders.stream().flatMap(h -> h.getScheduledTasks().stream()).map(Object::toString))
				.anyMatch(task -> task.contains("scheduledCleanup"));
	}
}
