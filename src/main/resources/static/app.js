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
	dialogCompany: document.querySelector("#dialog-company"),
	responseEmailLink: document.querySelector("#response-email-link"),
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
const trashInProgress = new Set();
const responsePreparationInProgress = new Set();
const responseSendInProgress = new Set();
const responseVisibilityStorageKey = "email-management-agent:hidden-responses";
const hiddenResponses = new Set();
try {
	const savedHiddenResponses = JSON.parse(localStorage.getItem(responseVisibilityStorageKey) || "[]");
	if (Array.isArray(savedHiddenResponses)) {
		savedHiddenResponses.map(Number).filter(Number.isSafeInteger).forEach(id => hiddenResponses.add(id));
	}
} catch {
	// Response visibility still works for this page if browser storage is unavailable.
}
const pageSize = 20;
let currentPage = 0;
let saving = false;
let workflowMessage = "";
let localImportRequest = false;
let remoteImportRunning = false;
let importStatusRequest = 0;
let currentDetailId = null;
let openDetailActions = null;

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
		FAILED: "Preparation failed · save to retry",
		SENDING: "Sending from Gmail",
		SENT: "Sent from Gmail",
		SENT_LABEL_FAILED: "Sent · test label could not be applied",
		SEND_FAILED: "Sending failed · retry",
		SEND_UNCONFIRMED: "Send outcome uncertain · check Gmail",
		AWAITING_REPLY: "Sent · waiting for a reply",
		CHECKING_RESPONSE: "Checking for a reply",
		FOLLOW_UP_SENT: "Follow-up sent · waiting for a reply",
		FOLLOW_UP_FAILED: "Follow-up failed · retrying automatically",
		REPLIED: "Recruiter replied"
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

function setResponseHidden(jobId, hidden) {
	if (hidden) {
		hiddenResponses.add(jobId);
	} else {
		hiddenResponses.delete(jobId);
	}
	try {
		localStorage.setItem(responseVisibilityStorageKey, JSON.stringify([...hiddenResponses]));
	} catch {
		// Keep the visibility change for this page if browser storage is unavailable.
	}
	const card = elements.jobList.querySelector(`[data-job-id="${jobId}"]`);
	if (card) {
		const previewText = card.querySelector(".response-preview-text");
		const previewToggle = card.querySelector(".response-preview-toggle");
		previewText.hidden = hidden;
		previewToggle.textContent = hidden ? "Show response" : "Hide response";
	}
	if (currentDetailId === jobId) {
		const detailText = elements.dialogBody.querySelector(".response-email-content");
		const detailToggle = elements.dialogBody.querySelector(".response-visibility-toggle");
		if (detailText) {
			detailText.hidden = hidden;
		}
		if (detailToggle) {
			detailToggle.textContent = hidden ? "Show response" : "Hide response";
		}
		elements.responseEmailLink.textContent = hidden ? "Show Response Email" : "Response Email";
	}
}

function renderJob(job) {
	const fragment = elements.template.content.cloneNode(true);
	const card = fragment.querySelector(".job-card");
	card.dataset.jobId = job.id;
	const checkbox = fragment.querySelector(".job-select");
	const date = job.receivedAt || job.savedAt;
	fragment.querySelector(".company-avatar").textContent = initials(contactName(job));
	fragment.querySelector(".job-title").textContent = valueOrFallback(job.title, "Architect opportunity");
	fragment.querySelector(".job-company").textContent = contactName(job);
	fragment.querySelector(".job-date").textContent = relativeDate(date);
	fragment.querySelector(".job-date").dateTime = date || "";
	fragment.querySelector(".job-summary").textContent = valueOrFallback(job.summary, "No summary was stored.");
	fragment.querySelector(".job-meta").textContent = jobMeta(job);
	const responseCreated = Boolean(job.responseContent)
		|| job.responseStatus === "DRAFT_CREATED"
		|| job.responseStatus === "SAVED_FOR_REVIEW";
	checkbox.checked = responseCreated || (pendingSelections.has(job.id) ? pendingSelections.get(job.id) : job.selectedForResponse);
	checkbox.disabled = responseCreated;
	checkbox.setAttribute("aria-label", `Select ${valueOrFallback(job.title, "opportunity")} to respond to`);
	checkbox.addEventListener("change", () => {
		if (trashInProgress.has(job.id)) {
			checkbox.checked = false;
			return;
		}
		workflowMessage = "";
		pendingSelections.set(job.id, checkbox.checked);
		updateOpenDetailActions();
		updateSelectionStatus();
	});
	fragment.querySelector(".job-open").addEventListener("click", () => openDetails(job.id));
	const responsePreview = fragment.querySelector(".response-preview");
	if (job.responseContent) {
		fragment.querySelector(".response-preview-text").textContent = job.responseContent;
		fragment.querySelector(".response-preview-text").hidden = hiddenResponses.has(job.id);
		const toggle = fragment.querySelector(".response-preview-toggle");
		toggle.textContent = hiddenResponses.has(job.id) ? "Show response" : "Hide response";
		toggle.addEventListener("click", () => setResponseHidden(job.id, !hiddenResponses.has(job.id)));
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
	const importJustEnabled = data.emailImportEnabled && elements.importEmails.classList.contains("hidden");
	elements.importEmails.classList.toggle("hidden", !data.emailImportEnabled);
	if (importJustEnabled) {
		syncImportStatus();
	}
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
	if (localImportRequest || elements.importEmails.disabled) {
		return;
	}
	localImportRequest = true;
	setImportRunning(true);
	elements.importNotice.textContent = "Classifying recent inbox emails and manually tagged architect emails. The import stops after saving 10 new architect opportunities.";
	elements.importNotice.classList.remove("hidden", "is-error");
	elements.importNotice.classList.add("is-loading");
	elements.error.classList.add("hidden");
	try {
		const response = await fetch("/api/dashboard/dev/import-architect-emails", { method: "POST" });
		if (response.status === 409) {
			localImportRequest = false;
			setImportRunning(true);
			return;
		}
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
		localImportRequest = false;
		await syncImportStatus();
	}
}

function setImportRunning(running) {
	elements.importEmails.disabled = running || localImportRequest;
	elements.importEmails.textContent = elements.importEmails.disabled ? "Import in progress…" : "Import recent Gmail emails";
	if (running && !localImportRequest) {
		remoteImportRunning = true;
		elements.importNotice.textContent = "An email import is already running.";
		elements.importNotice.classList.remove("hidden", "is-error");
		elements.importNotice.classList.add("is-loading");
	} else if (!running && remoteImportRunning) {
		remoteImportRunning = false;
		elements.importNotice.textContent = "The email import has finished.";
		elements.importNotice.classList.remove("hidden", "is-loading", "is-error");
	}
}

async function syncImportStatus() {
	if (elements.importEmails.classList.contains("hidden")) {
		return;
	}
	const request = ++importStatusRequest;
	try {
		const response = await fetch("/api/dashboard/dev/import-architect-emails/status", {
			cache: "no-store",
			headers: { Accept: "application/json" }
		});
		if (response.ok) {
			const status = await response.json();
			if (request === importStatusRequest) {
				setImportRunning(status.running === true);
			}
		}
	} catch {
		// The SSE stream will provide the next status change if this one-time sync fails.
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

function responseDetailSection(job) {
	const section = document.createElement("section");
	section.className = "detail-section response-detail-section";
	section.id = "response-email";
	const headingRow = document.createElement("div");
	headingRow.className = "response-section-heading";
	const heading = document.createElement("h3");
	heading.textContent = "Prepared response";
	const toggle = document.createElement("button");
	toggle.className = "response-visibility-toggle";
	toggle.type = "button";
	const content = document.createElement("p");
	content.className = "response-email-content";
	content.textContent = job.responseContent;
	const updateVisibility = () => {
		const hidden = hiddenResponses.has(job.id);
		content.hidden = hidden;
		toggle.textContent = hidden ? "Show response" : "Hide response";
		elements.responseEmailLink.textContent = hidden ? "Show Response Email" : "Response Email";
	};
	toggle.addEventListener("click", () => setResponseHidden(job.id, !hiddenResponses.has(job.id)));
	headingRow.append(heading, toggle);
	section.append(headingRow, content);
	updateVisibility();
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
			html, body { margin: 0; }
			body { padding: 8px; }
		`;
		frame.srcdoc = `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; base-uri 'none'; form-action 'none'; object-src 'none'; frame-src 'none'; script-src 'none'; connect-src 'none'; img-src data: http: https:; style-src 'unsafe-inline' http: https:; font-src data: http: https:"><meta name="viewport" content="width=device-width, initial-scale=1"><style>${frameStyles}</style></head><body>${job.htmlBody}</body></html>`;
		section.append(heading, frame);
	} else {
		const content = document.createElement("pre");
		content.className = "email-plain-fallback";
		content.textContent = valueOrFallback(job.description, "No email body was stored.");
		section.append(heading, content);
	}
	return section;
}

function updateOpenDetailActions() {
	if (!openDetailActions) {
		return;
	}
	const { job, prepareButton, sendButton, trashButton, status } = openDetailActions;
	const responseCreated = Boolean(job.responseContent)
		|| job.responseStatus === "DRAFT_CREATED"
		|| job.responseStatus === "SAVED_FOR_REVIEW";
	const responseFinished = ["SAVED_FOR_REVIEW", "DRAFT_CREATED", "SENT", "SENT_LABEL_FAILED",
		"AWAITING_REPLY", "CHECKING_RESPONSE", "FOLLOW_UP_SENT", "FOLLOW_UP_FAILED", "REPLIED"].includes(job.responseStatus);
	const selectedForResponse = !responseFinished && (job.selectedForResponse || pendingSelections.get(job.id) === true);
	const preparingReply = responsePreparationInProgress.has(job.id)
		|| job.responseStatus === "GENERATING" || job.responseStatus === "GENERATED";
	const sendingReply = responseSendInProgress.has(job.id);
	const trashing = trashInProgress.has(job.id);
	const retrying = job.responseStatus === "FAILED";
	const sendable = Boolean(job.responseContent)
		&& Boolean(job.sourceAccount)
		&& Boolean(job.recruiterEmail)
		&& ["DRAFT_CREATED", "SAVED_FOR_REVIEW", "SEND_FAILED", "SEND_UNCONFIRMED", "SENDING"].includes(job.responseStatus);
	prepareButton.hidden = responseCreated && !retrying;
	prepareButton.disabled = preparingReply || trashing || (responseCreated && !retrying);
	prepareButton.textContent = preparingReply ? "Preparing response…" : retrying ? "Retry response" : "Prepare response";
	sendButton.hidden = !sendable;
	sendButton.disabled = sendingReply || trashing;
	sendButton.textContent = sendingReply ? "Checking Gmail…"
		: ["SEND_UNCONFIRMED", "SENDING"].includes(job.responseStatus) ? "Check Gmail send"
		: job.responseStatus === "SEND_FAILED" ? "Retry send" : "Send reply";
	trashButton.disabled = selectedForResponse || preparingReply || trashing;
	status.textContent = trashing
		? "Moving email to Trash…"
		: sendingReply
		? "Checking or sending the reply through Gmail…"
		: preparingReply
			? "Your response is being prepared."
			: selectedForResponse
				? "This email is selected for a reply. Clear the reply checkbox and save the change before moving it to Trash."
				: "";
}

function detailEmailActions(job) {
	const action = document.createElement("div");
	action.className = "detail-section detail-email-actions";
	const prepareButton = document.createElement("button");
	prepareButton.className = "primary-button";
	prepareButton.type = "button";
	const sendButton = document.createElement("button");
	sendButton.className = "primary-button";
	sendButton.type = "button";
	const trashButton = document.createElement("button");
	trashButton.className = "secondary-button";
	trashButton.type = "button";
	trashButton.textContent = "Move to Gmail Trash";
	trashButton.hidden = !job.sourceAccount;
	const status = document.createElement("p");
	status.setAttribute("role", "status");
	openDetailActions = { job, prepareButton, sendButton, trashButton, status };
	updateOpenDetailActions();
	prepareButton.addEventListener("click", async () => {
		responsePreparationInProgress.add(job.id);
		updateOpenDetailActions();
		try {
			const response = await fetch(`/api/dashboard/jobs/${job.id}/response`, { method: "POST" });
		const result = await response.json();
			if (!response.ok) {
				throw new Error(result.message || "Unable to prepare this response");
			}
			pendingSelections.delete(job.id);
			workflowMessage = "Response preparation started for this opportunity.";
			await Promise.all([
				loadDashboard(elements.searchInput.value, currentPage),
				refreshDetails(job.id)
			]);
		} catch (error) {
			responsePreparationInProgress.delete(job.id);
			updateOpenDetailActions();
			status.textContent = error.message || "Unable to prepare this response. Please try again.";
		}
	});
	sendButton.addEventListener("click", async () => {
		if (!["SEND_UNCONFIRMED", "SENDING"].includes(job.responseStatus)
				&& !window.confirm(`Send this reply now? Production sends it to the recruiter. All other profiles send it to the source mailbox and apply the Gmail label “EMail Agent Test”.`)) {
			return;
		}
		responseSendInProgress.add(job.id);
		updateOpenDetailActions();
		try {
			const response = await fetch(`/api/dashboard/jobs/${job.id}/response/send`, { method: "POST" });
			if (!response.ok) {
				const result = await response.json().catch(() => ({}));
				throw new Error(result.message || "Gmail could not send this reply");
			}
			await Promise.all([
				loadDashboard(elements.searchInput.value, currentPage),
				refreshDetails(job.id)
			]);
		} catch (error) {
			responseSendInProgress.delete(job.id);
			updateOpenDetailActions();
			status.textContent = error.message || "Gmail could not send this reply. Please try again.";
		}
	});
	trashButton.addEventListener("click", async () => {
		if (!window.confirm(`Move this email to Trash in ${job.sourceAccount} and remove the job from the dashboard?`)) {
			return;
		}
		trashInProgress.add(job.id);
		updateOpenDetailActions();
		try {
			const response = await fetch(`/api/dashboard/jobs/${job.id}/email`, { method: "DELETE" });
			if (!response.ok) {
				if (response.status === 409) {
					status.textContent = "Clear any saved reply selection or wait for the current action to finish.";
					return;
				}
				status.textContent = response.status === 404
					? "This job no longer has an available source email. Refresh the dashboard."
					: "Could not move the email to Trash. Please try again.";
				return;
			}
			pendingSelections.delete(job.id);
			if (action.isConnected && elements.dialog.open) {
				elements.dialog.close();
			}
			await loadDashboard();
		} catch {
			status.textContent = "Could not reach the dashboard. Refresh before trying again.";
		} finally {
			trashInProgress.delete(job.id);
			updateOpenDetailActions();
		}
	});
	action.append(prepareButton, sendButton, trashButton, status);
	return action;
}

function renderDetails(job) {
	openDetailActions = null;
	elements.dialogTitle.textContent = valueOrFallback(job.title, "Architect opportunity");
	elements.dialogCompany.textContent = valueOrFallback(job.company);
	elements.responseEmailLink.classList.toggle("hidden", !job.responseContent);
	if (job.responseContent) {
		elements.responseEmailLink.textContent = hiddenResponses.has(job.id) ? "Show Response Email" : "Response Email";
	}
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
	const responseSection = job.responseContent ? responseDetailSection(job) : null;
	elements.dialogBody.replaceChildren(
		grid,
		detailEmailActions(job),
		detailSection("Email Summary", job.summary),
		detailSection("Response status", responseStatusLabel(job.responseStatus) || "Not selected"),
		...(responseSection ? [responseSection] : []),
		...(job.gmailDraftId ? [detailSection("Gmail draft ID", job.gmailDraftId)] : []),
		detailSection("Requirements", job.requirements),
		originalEmailSection(job)
	);
}

async function refreshDetails(id) {
	try {
		const response = await fetch(`/api/dashboard/jobs/${id}`, { headers: { Accept: "application/json" } });
		if (!response.ok) {
			return;
		}
		const job = await response.json();
		if (currentDetailId !== id || !elements.dialog.open) {
			return;
		}
		if (job.responseStatus !== "GENERATING" && job.responseStatus !== "GENERATED") {
			responsePreparationInProgress.delete(id);
		}
		if (job.responseStatus !== "SENDING") {
			responseSendInProgress.delete(id);
		}
		renderDetails(job);
	} catch {
		// The existing detail view remains visible if a status refresh fails.
	}
}

async function openDetails(id) {
	currentDetailId = id;
	elements.dialogTitle.textContent = "Loading…";
	elements.dialogCompany.textContent = "";
	elements.responseEmailLink.classList.add("hidden");
	elements.dialogBody.replaceChildren();
	elements.dialog.showModal();
	try {
		const response = await fetch(`/api/dashboard/jobs/${id}`, { headers: { Accept: "application/json" } });
		if (!response.ok) {
			throw new Error(`Job request failed with status ${response.status}`);
		}
		const job = await response.json();
		if (currentDetailId === id) {
			if (job.responseStatus !== "GENERATING" && job.responseStatus !== "GENERATED") {
				responsePreparationInProgress.delete(id);
			}
			if (job.responseStatus !== "SENDING") {
				responseSendInProgress.delete(id);
			}
			renderDetails(job);
		}
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
elements.responseEmailLink.addEventListener("click", event => {
	if (currentDetailId === null || !hiddenResponses.has(currentDetailId)) {
		return;
	}
	event.preventDefault();
	setResponseHidden(currentDetailId, false);
	elements.dialogBody.querySelector(".response-email-content")
		?.scrollIntoView({ behavior: "smooth", block: "nearest" });
});
elements.dialog.addEventListener("close", () => {
	currentDetailId = null;
	openDetailActions = null;
});
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
	syncImportStatus();
});
dashboardEvents.addEventListener("import-state", syncImportStatus);
dashboardEvents.addEventListener("opportunity-added", () => loadDashboard(elements.searchInput.value, currentPage));
dashboardEvents.addEventListener("opportunity-updated", () => loadDashboard(elements.searchInput.value, currentPage));
dashboardEvents.addEventListener("opportunity-removed", () => loadDashboard(elements.searchInput.value, currentPage));
dashboardEvents.addEventListener("response-updated", () => {
	loadDashboard(elements.searchInput.value, currentPage);
	if (currentDetailId !== null) {
		refreshDetails(currentDetailId);
	}
});

async function watchDevelopmentAssets() {
	let lastVersion;
	let polling = true;
	const checkVersion = async () => {
		if (!polling) {
			return;
		}
		try {
			const response = await fetch("/api/dev/static-version", { cache: "no-store" });
			if (!response.ok) {
				polling = false;
				return;
			}
			const version = await response.text();
			if (lastVersion && version !== lastVersion) {
				window.location.reload();
				return;
			}
			lastVersion = version;
		} catch {
			polling = false;
		}
	};
	await checkVersion();
	if (polling) {
		window.setInterval(checkVersion, 1_000);
	}
}

watchDevelopmentAssets();

loadDashboard();
