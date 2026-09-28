package com.ultiweb.jobs.svc.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.ultiweb.jobs.utils.oauth2.GMailOAuth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LabelSvcTest {
	@Mock private GMailOAuth gmailOAuth;
	@Mock(answer = Answers.RETURNS_DEEP_STUBS) private Gmail gmail;
	private LabelSvc labelSvc;

	@BeforeEach
	void setUp() {
		labelSvc = spy(new LabelSvc(gmailOAuth));
	}

	@Test
	void listsLabelsForTheConfiguredSingleAccount() throws Exception {
		// given
		when(gmailOAuth.singleAccount()).thenReturn("first@example.com");
		doReturn(gmail).when(labelSvc).gmail("first@example.com");
		final List<Label> labels = List.of(new Label().setId("label-id").setName("Architect_Jobs"));
		when(gmail.users().labels().list("me").execute())
				.thenReturn(new ListLabelsResponse().setLabels(labels));

		// when
		final List<Label> result = labelSvc.listLabels();

		// then
		assertEquals(labels, result);
		verify(labelSvc).gmail("first@example.com");
	}

	@Test
	void returnsAnEmptyListWhenGmailHasNoLabels() throws Exception {
		// given
		doReturn(gmail).when(labelSvc).gmail("second@example.com");
		when(gmail.users().labels().list("me").execute())
				.thenReturn(new ListLabelsResponse());

		// when
		final List<Label> result = labelSvc.listLabels("second@example.com");

		// then
		assertEquals(List.of(), result);
	}
}
