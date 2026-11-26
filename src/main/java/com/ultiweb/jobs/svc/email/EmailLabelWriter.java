package com.ultiweb.jobs.svc.email;

import java.io.IOException;

public interface EmailLabelWriter {
	void applyLabel(String account, String messageId, String labelName) throws IOException;

	/**
	 * Adds a label and removes the message from the inbox in a single Gmail modification, so the
	 * message is never briefly labelled without also being archived.
	 *
	 * @param account the mailbox that owns the message
	 * @param messageId the Gmail message id
	 * @param labelName the label to add; created when missing
	 */
	void applyLabelAndArchive(String account, String messageId, String labelName) throws IOException;
}
