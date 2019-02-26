package org.sagebionetworks.bridge.play.controllers;

import static org.sagebionetworks.bridge.Roles.DEVELOPER;
import static org.sagebionetworks.bridge.Roles.RESEARCHER;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import org.junit.Test;
import play.mvc.Result;

import org.sagebionetworks.bridge.TestConstants;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.services.ExportService;

public class ExportControllerTest {
    @Test
    public void test() throws Exception {
        // spy controller
        ExportController controller = spy(new ExportController());

        // mock session
        UserSession mockSession = new UserSession();
        mockSession.setStudyIdentifier(TestConstants.TEST_STUDY);
        doReturn(mockSession).when(controller).getAuthenticatedSession(any());

        // mock service
        ExportService mockExportService = mock(ExportService.class);
        controller.setExportService(mockExportService);

        // execute and validate
        Result result = controller.startOnDemandExport();
        TestUtils.assertResult(result, 202);
        verify(mockExportService).startOnDemandExport(TestConstants.TEST_STUDY);
        verify(controller).getAuthenticatedSession(DEVELOPER);
    }
}
