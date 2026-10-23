package com.tca.UserManager.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tca.UserManager.service.DemoContentCleanupService;

/** Operational endpoints for admins (all paths under /api/admin require ADMIN). */
@RestController
@RequestMapping("/admin")
public class AdminController {

	private final DemoContentCleanupService demoContentCleanupService;

	public AdminController(DemoContentCleanupService demoContentCleanupService) {
		this.demoContentCleanupService = demoContentCleanupService;
	}

	/** Removes disposable demo content older than the retention window. */
	@PreAuthorize("hasRole('ADMIN')")
	@PostMapping("/demo-content/cleanup")
	public DemoContentCleanupService.Result cleanupDemoContent() {
		return demoContentCleanupService.cleanup();
	}
}
