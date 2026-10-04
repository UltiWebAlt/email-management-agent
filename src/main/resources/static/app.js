const elements = {
	totalJobs: document.querySelector("#total-jobs"),
	recentJobs: document.querySelector("#recent-jobs"),
	recruiters: document.querySelector("#recruiters"),
	followUps: document.querySelector("#follow-ups"),
	lastSaved: document.querySelector("#last-saved"),
	resultCount: document.querySelector("#result-count"),
	jobList: document.querySelector("#job-list"),
	activityList: document.querySelector("#activity-list"),
	emptyState: document.querySelector("#empty-state"),
	emptyCopy: document.querySelector("#empty-copy"),
	error: document.querySelector("#error-message"),
	searchForm: document.querySelector("#search-form"),
	searchInput: document.querySelector("#search-input"),
	sortOrder: document.querySelector("#sort-order"),
	refresh: document.querySelector("#refresh"),
	template: document.querySelector("#job-template"),
	dialog: document.querySelector("#job-dialog"),
	dialogTitle: document.querySelector("#dialog-title"),
	dialogBody: document.querySelector("#dialog-body"),
	closeDialog: document.querySelector("#close-dialog"),
	saveSelections: document.querySelector("#save-selections"),
	selectionStatus: document.querySelector("#selection-status"),
	importEmails: document.querySelector("#import-emails"),
	importNotice: document.querySelector("#import-notice"),
	pagination: document.querySelector("#pagination"),
	pageDescription: document.querySelector("#page-description"),
	previousPage: document.querySelector("#previous-page"),
	nextPage: document.querySelector("#next-page")
};

const pendingSelections = new Map();
const pageSize = 20;
let currentPage = 0;
let saving = false;
let workflowMessage = "";

const dateFormatter = new Intl.DateTimeFormat(undefined, {
	month: "short",
	day: "numeric",
	year: "numeric"
});

const relativeFormatter = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });

function valueOrFallback(value, fallback = "Not available") {
	return value && String(value).trim() ? value : fallback;
}

function formatDate(value) {
	if (!value) {
		return "Date unknown";
	}
	return dateFormatter.format(new Date(value));
}

function relativeDate(value) {
	if (!value) {
		return "Date unknown";
	}
	const elapsedDays = Math.round((new Date(value).getTime() - Date.now()) / 86_400_000);
	if (Math.abs(elapsedDays) < 30) {
		return relativeFormatter.format(elapsedDays, "day");
	}
	return formatDate(value);
}

function contactName(job) {
	return valueOrFallback(job.company, valueOrFallback(job.recruiterName, job.recruiterEmail));
}

function initials(value) {
	return valueOrFallback(value, "J")
		.split(/\s+/)
		.slice(0, 2)
		.map(part => part.charAt(0).toUpperCase())
		.join("");
}

function jobMeta(job) {
	const pieces = [];
	if (job.location) {
		pieces.push(job.location);
	}
	if (job.remote === true) {
		pieces.push("Remote");
	}
	if (job.recruiterEmail) {
		pieces.push(job.recruiterEmail);
	}
	return pieces.join("  ·  ") || "Captured architect opportunity";
}

function responseStatusLabel(status) {
	const labels = {
		GENERATING: "Preparing response",
		GENERATED: "Draft body ready",
		DRAFT_CREATED: "Gmail draft created",
		SAVED_FOR_REVIEW: "Saved in development database",
		FAILED: "Preparation failed · save to retry"
	};
	return labels[status] || "";
}

function updateSelectionStatus() {
	const pending = pendingSelections.size;
	const selectedOnPage = [...elements.jobList.querySelectorAll(".job-select:checked")].length;
	elements.selectionStatus.textContent = pending
		? `${pending} unsaved selection ${pending === 1 ? "change" : "changes"} across pages · ${selectedOnPage} selected on this page`
		: workflowMessage || `${selectedOnPage} selected on this page. Saved selections are kept across pages.`;
}

function renderJob(job) {
	const fragment = elements.template.content.cloneNode(true);
	const card = fragment.querySelector(".job-card");
	const checkbox = fragment.querySelector(".job-select");
	const date = job.receivedAt || job.savedAt;
	fragment.querySelector(".company-avatar").textContent = initials(contactName(job));
	fragment.querySelector(".job-title").textContent = valueOrFallback(job.title, "Architect opportunity");
	fragment.querySelector(".job-company").textContent = contactName(job);
	fragment.querySelector(".job-date").textContent = relativeDate(date);
	fragment.querySelector(".job-date").dateTime = date || "";
	fragment.querySelector(".job-summary").textContent = valueOrFallback(job.summary, "No summary was stored.");
	fragment.querySelector(".job-meta").textContent = jobMeta(job);
	checkbox.checked = pendingSelections.has(job.id) ? pendingSelections.get(job.id) : job.selectedForResponse;
	checkbox.setAttribute("aria-label", `Select ${valueOrFallback(job.title, "opportunity")} to respond to`);
	checkbox.addEventListener("change", () => {
		workflowMessage = "";
		pendingSelections.set(job.id, checkbox.checked);
		updateSelectionStatus();
	});
	fragment.querySelector(".job-open").addEventListener("click", () => openDetails(job.id));
	const responsePreview = fragment.querySelector(".response-preview");
	if (job.responseContent) {
		fragment.querySelector(".response-preview-text").textContent = job.responseContent;
		responsePreview.classList.remove("hidden");
	}
	const status = fragment.querySelector(".response-status");
	const statusLabel = responseStatusLabel(job.responseStatus);
	if (statusLabel) {
		status.textContent = statusLabel;
		status.classList.remove("hidden");
		status.classList.add(`status-${job.responseStatus.toLowerCase()}`);
	}
	return fragment;
}

function renderActivity(jobs) {
	elements.activityList.replaceChildren();
	if (!jobs.length) {
		const copy = document.createElement("p");
		copy.className = "job-company";
		copy.textContent = "Activity will appear after the first architect job is captured.";
		elements.activityList.append(copy);
		return;
	}
	jobs.slice(0, 5).forEach(job => {
		const item = document.createElement("div");
		item.className = "timeline-item";
		const title = document.createElement("strong");
		title.textContent = valueOrFallback(job.title, "Architect opportunity");
		const when = document.createElement("span");
		when.textContent = `Saved ${relativeDate(job.savedAt)} · ${contactName(job)}`;
		item.append(title, when);
		elements.activityList.append(item);
	});
}

function renderDashboard(data) {
	elements.totalJobs.textContent = data.metrics.totalJobs.toLocaleString();
	elements.recentJobs.textContent = data.metrics.recentJobs.toLocaleString();
	elements.recruiters.textContent = data.metrics.recruiters.toLocaleString();
	elements.followUps.textContent = data.metrics.followUps.toLocaleString();
	elements.lastSaved.textContent = data.metrics.lastSavedAt ? relativeDate(data.metrics.lastSavedAt) : "No jobs yet";
	currentPage = data.page;
	const firstResult = data.totalResults === 0 ? 0 : data.page * data.pageSize + 1;
	const lastResult = Math.min(data.totalResults, (data.page + 1) * data.pageSize);
	elements.resultCount.textContent = `${firstResult}–${lastResult} of ${data.totalResults}`;
	elements.jobList.replaceChildren(...data.jobs.map(renderJob));
	elements.pagination.classList.toggle("hidden", data.totalPages <= 1);
	elements.pageDescription.textContent = `Page ${data.page + 1} of ${data.totalPages}`;
	elements.previousPage.disabled = data.page <= 0;
	elements.nextPage.disabled = data.page + 1 >= data.totalPages;
	elements.importEmails.classList.toggle("hidden", !data.emailImportEnabled);
	elements.emptyState.classList.toggle("hidden", data.jobs.length > 0);
	elements.emptyCopy.textContent = data.query
		? `Nothing matched “${data.query}”. Try a broader search.`
		: "Architect jobs will appear here after the email agent persists them.";
	renderActivity(data.jobs);
	updateSelectionStatus();
}

async function loadDashboard(query = elements.searchInput.value, page = currentPage, sort = elements.sortOrder.value) {
	elements.error.classList.add("hidden");
	elements.resultCount.textContent = "Loading…";
	elements.sortOrder.value = sort;
	try {
		const response = await fetch(`/api/dashboard?query=${encodeURIComponent(query)}&page=${page}&pageSize=${pageSize}&sort=${encodeURIComponent(sort)}`, {
			headers: { Accept: "application/json" }
		});
		if (!response.ok) {
			throw new Error(`Dashboard request failed with status ${response.status}`);
		}
		renderDashboard(await response.json());
	} catch (error) {
		elements.resultCount.textContent = "Unavailable";
		elements.error.textContent = "The dashboard data could not be loaded. Confirm the Spring Boot application is running and try again.";
		elements.error.classList.remove("hidden");
	}
}

async function saveSelections() {
	if (saving) {
		return;
	}
	saving = true;
	elements.saveSelections.disabled = true;
	elements.selectionStatus.textContent = "Saving selections and preparing selected replies…";
	elements.error.classList.add("hidden");
	try {
		const response = await fetch("/api/dashboard/selections", {
			method: "POST",
			headers: { "Content-Type": "application/json", Accept: "application/json" },
			body: JSON.stringify({
				changes: [...pendingSelections].map(([positionId, selected]) => ({ positionId, selected }))
			})
		});
		const result = await response.json();
		if (!response.ok) {
			throw new Error(result.message || "Unable to save response selections");
		}
		pendingSelections.clear();
		workflowMessage = result.mode === "development"
			? `Selections saved. ${result.queuedResponses} response(s) will be stored in the development database.`
			: `Selections saved. Preparing ${result.queuedResponses} Gmail draft(s) for review.`;
		await loadDashboard(elements.searchInput.value, currentPage);
	} catch (error) {
		elements.error.textContent = error.message || "Selections could not be saved. Your unsaved changes are still on this page.";
		elements.error.classList.remove("hidden");
	} finally {
		saving = false;
		elements.saveSelections.disabled = false;
	}
}

async function importArchitectEmails() {
	elements.importEmails.disabled = true;
	elements.importEmails.textContent = "Importing emails…";
	elements.importNotice.textContent = "Classifying recent inbox emails and manually tagged architect emails. The import stops after saving 10 new architect opportunities.";
	elements.importNotice.classList.remove("hidden", "is-error");
	elements.importNotice.classList.add("is-loading");
	elements.error.classList.add("hidden");
	try {
		const response = await fetch("/api/dashboard/dev/import-architect-emails", { method: "POST" });
		const result = await response.json();
		if (!response.ok) {
			throw new Error(result.message || "Gmail import failed");
		}
		const summary = result.scanned === 0
			? "Import complete. No recent inbox or manually tagged architect emails were found."
			: `Import complete. Scanned ${result.scanned}; added ${result.imported}; enriched ${result.enriched} existing opportunities; already complete ${result.skipped}; not architect jobs ${result.notArchitectJobs}; failed ${result.failed}.`;
		workflowMessage = `${summary} Gmail labels were not changed.`;
		elements.importNotice.textContent = `${workflowMessage} You can import again after tagging more emails.`;
		elements.importNotice.classList.remove("is-loading");
		await loadDashboard(elements.searchInput.value, 0);
	} catch (error) {
		elements.importNotice.textContent = error.message || "The development import could not complete. Please try again.";
		elements.importNotice.classList.remove("is-loading");
		elements.importNotice.classList.add("is-error");
	} finally {
		elements.importEmails.disabled = false;
		elements.importEmails.textContent = "Import recent Gmail emails";
	}
}

function detailField(label, value) {
	const field = document.createElement("div");
	field.className = "detail-field";
	const key = document.createElement("span");
	key.textContent = label;
	const content = document.createElement("strong");
	content.textContent = valueOrFallback(value);
	field.append(key, content);
	return field;
}

function detailSection(title, value) {
	const section = document.createElement("section");
	section.className = "detail-section";
	const heading = document.createElement("h3");
	heading.textContent = title;
	const content = document.createElement("p");
	content.textContent = valueOrFallback(value);
	section.append(heading, content);
	return section;
}

function originalEmailSection(job) {
	const section = document.createElement("section");
	section.className = "detail-section email-content-section";
	const heading = document.createElement("h3");
	heading.textContent = "Original email";
	if (job.htmlBody) {
		const frame = document.createElement("iframe");
		frame.className = "email-html-frame";
		frame.title = "Formatted original email content";
		frame.setAttribute("sandbox", "allow-popups allow-popups-to-escape-sandbox");
		frame.setAttribute("referrerpolicy", "no-referrer");
		const frameStyles = `
			* { box-sizing: border-box; max-width: 100% !important; }
			html, body { width: 100%; max-width: 100%; margin: 0; overflow-x: hidden; }
			body { padding: 16px; font: 14px/1.55 system-ui, sans-serif; color: #253047; overflow-wrap: anywhere; }
			img, video, svg { max-width: 100% !important; height: auto !important; }
			table { width: 100% !important; max-width: 100% !important; table-layout: fixed !important; border-collapse: collapse; }
			td, th { max-width: 100% !important; padding: 5px; border: 1px solid #d8deea; white-space: normal !important; overflow-wrap: anywhere !important; }
			pre, code { white-space: pre-wrap !important; overflow-wrap: anywhere !important; }
			a { color: #3158a8; overflow-wrap: anywhere !important; }
		`;
		frame.srcdoc = `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src data:"><meta name="viewport" content="width=device-width, initial-scale=1"><style>${frameStyles}</style></head><body>${job.htmlBody}</body></html>`;
		section.append(heading, frame);
	} else {
		const content = document.createElement("pre");
		content.className = "email-plain-fallback";
		content.textContent = valueOrFallback(job.description, "No email body was stored.");
		section.append(heading, content);
	}
	return section;
}

function renderDetails(job) {
	elements.dialogTitle.textContent = valueOrFallback(job.title, "Architect opportunity");
	const grid = document.createElement("div");
	grid.className = "detail-grid";
	grid.append(
		detailField("Company", job.company),
		detailField("Recruiter", job.recruiterName || job.recruiterEmail),
		detailField("Location", job.remote === true ? `${valueOrFallback(job.location, "Remote")} · Remote` : job.location),
		detailField("Received", formatDate(job.receivedAt)),
		detailField("Salary", job.salaryRange),
		detailField("Responses", String(job.responseCount)),
		detailField("Source account", job.sourceAccount),
		detailField("Sender", job.sourceSender)
	);
	elements.dialogBody.replaceChildren(
		grid,
		detailSection("Email Summary", job.summary),
		detailSection("Response status", responseStatusLabel(job.responseStatus) || "Not selected"),
		...(job.responseContent ? [detailSection("Prepared response", job.responseContent)] : []),
		...(job.gmailDraftId ? [detailSection("Gmail draft ID", job.gmailDraftId)] : []),
		detailSection("Requirements", job.requirements),
		originalEmailSection(job)
	);
}

async function openDetails(id) {
	elements.dialogTitle.textContent = "Loading…";
	elements.dialogBody.replaceChildren();
	elements.dialog.showModal();
	try {
		const response = await fetch(`/api/dashboard/jobs/${id}`, { headers: { Accept: "application/json" } });
		if (!response.ok) {
			throw new Error(`Job request failed with status ${response.status}`);
		}
		renderDetails(await response.json());
	} catch (error) {
		elements.dialogTitle.textContent = "Unable to load opportunity";
		elements.dialogBody.append(detailSection("Try again", "Close this panel, refresh the dashboard, and try again."));
	}
}

elements.searchForm.addEventListener("submit", event => {
	event.preventDefault();
	loadDashboard(elements.searchInput.value, 0);
});

elements.refresh.addEventListener("click", () => loadDashboard());
elements.sortOrder.addEventListener("change", () => loadDashboard(elements.searchInput.value, 0));
elements.saveSelections.addEventListener("click", saveSelections);
elements.importEmails.addEventListener("click", importArchitectEmails);
elements.previousPage.addEventListener("click", () => loadDashboard(elements.searchInput.value, Math.max(0, currentPage - 1)));
elements.nextPage.addEventListener("click", () => loadDashboard(elements.searchInput.value, currentPage + 1));
elements.closeDialog.addEventListener("click", () => elements.dialog.close());
elements.dialog.addEventListener("click", event => {
	if (event.target === elements.dialog) {
		elements.dialog.close();
	}
});

let dashboardStreamConnected = false;
const dashboardEvents = new EventSource("/api/dashboard/events");
dashboardEvents.addEventListener("connected", () => {
	if (dashboardStreamConnected) {
		loadDashboard(elements.searchInput.value, currentPage);
	}
	dashboardStreamConnected = true;
});
dashboardEvents.addEventListener("opportunity-added", () => loadDashboard(elements.searchInput.value, currentPage));
dashboardEvents.addEventListener("opportunity-updated", () => loadDashboard(elements.searchInput.value, currentPage));
dashboardEvents.addEventListener("response-updated", () => loadDashboard(elements.searchInput.value, currentPage));

loadDashboard();
