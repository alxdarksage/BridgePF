package org.sagebionetworks.bridge.dynamodb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.bridge.TestConstants.TEST_STUDY;

import javax.annotation.Resource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.models.ReportTypeResourceList;
import org.sagebionetworks.bridge.models.reports.ReportDataKey;
import org.sagebionetworks.bridge.models.reports.ReportIndex;
import org.sagebionetworks.bridge.models.reports.ReportType;

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;

@ContextConfiguration("classpath:test-context.xml")
@RunWith(SpringJUnit4ClassRunner.class)
public class DynamoReportIndexDaoTest {

    @Autowired
    DynamoReportIndexDao dao;
    
    private ReportDataKey studyReportKey1;
    
    private ReportDataKey studyReportKey2;
    
    private ReportDataKey participantReportKey1;
    
    private ReportDataKey participantReportKey2;

    @Resource(name = "reportIndexMapper")
    private DynamoDBMapper mapper;

    @Before
    public void before() {
        String reportName1 = TestUtils.randomName(DynamoReportIndexDaoTest.class);
        String reportName2 = TestUtils.randomName(DynamoReportIndexDaoTest.class);
        
        ReportDataKey.Builder builder = new ReportDataKey.Builder().withStudyIdentifier(TEST_STUDY);
        builder.withReportType(ReportType.STUDY);
        studyReportKey1 = builder.withIdentifier(reportName1).build();
        studyReportKey2 = builder.withIdentifier(reportName2).build();
        
        builder.withReportType(ReportType.PARTICIPANT);
        participantReportKey1 = builder.withIdentifier(reportName1)
                .withHealthCode(BridgeUtils.generateGuid()).build();
        participantReportKey2 = builder.withIdentifier(reportName2)
                .withHealthCode(BridgeUtils.generateGuid()).build();
    }
    
    @After
    public void after() {
        dao.removeIndex(studyReportKey1);
        dao.removeIndex(studyReportKey2);
        dao.removeIndex(participantReportKey1);
        dao.removeIndex(participantReportKey2);
    }
    
    @Test
    public void getIndexNoIndex() {
        ReportDataKey key = new ReportDataKey.Builder()
                .withStudyIdentifier(TEST_STUDY)
                .withIdentifier("invalid-identifier")
                .withReportType(ReportType.STUDY).build();
        
        ReportIndex index = dao.getIndex(key);
        assertNull(index);
    }
    
    @Test
    public void getIndexThatExists() {
        dao.addIndex(studyReportKey1);
        
        ReportIndex index = dao.getIndex(studyReportKey1);
        assertEquals(studyReportKey1.getIdentifier(), index.getIdentifier());
        assertEquals(studyReportKey1.getIndexKeyString(), index.getKey());
    }
    
    @Test
    public void canCRUDReportIndex() {
        int studyIndexCount = dao.getIndices(TEST_STUDY, ReportType.STUDY).getItems().size();
        int participantIndexCount = dao.getIndices(TEST_STUDY, ReportType.PARTICIPANT).getItems().size();
        // adding twice is fine
        dao.addIndex(studyReportKey1);
        dao.addIndex(studyReportKey1);
        dao.addIndex(studyReportKey2);

        ReportTypeResourceList<? extends ReportIndex> indices = dao.getIndices(TEST_STUDY, ReportType.STUDY);
        assertEquals(studyIndexCount+2, indices.getItems().size());
        
        // Update studyReportKey2
        ReportIndex index = dao.getIndex(studyReportKey2);
        index.setPublic(true);
        dao.updateIndex(index);

        index = dao.getIndex(studyReportKey2);
        assertTrue(index.isPublic());

        index.setPublic(false);
        dao.updateIndex(index);

        index = dao.getIndex(studyReportKey2);
        assertFalse(index.isPublic());

        // Wrong type returns same count as before
        indices = dao.getIndices(TEST_STUDY, ReportType.PARTICIPANT);
        assertEquals(participantIndexCount, indices.getItems().size());
        
        dao.removeIndex(studyReportKey1);
        indices = dao.getIndices(TEST_STUDY, ReportType.STUDY);
        assertEquals(studyIndexCount+1, indices.getItems().size());
        
        dao.removeIndex(studyReportKey2);
        indices = dao.getIndices(TEST_STUDY, ReportType.STUDY);
        assertEquals(studyIndexCount, indices.getItems().size());
    }
    
    @Test
    public void addingExistingIndexDoesNotDeleteMetadata() {
        dao.addIndex(studyReportKey1);

        ReportIndex index = dao.getIndex(studyReportKey1);
        index.setPublic(true);
        dao.updateIndex(index);
        
        // Now, adding this again should not delete the public = true status of index.
        dao.addIndex(studyReportKey1);

        index = dao.getIndex(studyReportKey1);
        assertTrue(index.isPublic());
    }
    
    @Test
    public void canCreateAndReadParticipantIndex() {
        int studyIndexCount = dao.getIndices(TEST_STUDY, ReportType.STUDY).getItems().size();
        int participantIndexCount = dao.getIndices(TEST_STUDY, ReportType.PARTICIPANT).getItems().size();
        
        // adding twice is fine
        dao.addIndex(participantReportKey1);
        dao.addIndex(participantReportKey1);
        dao.addIndex(participantReportKey2);
        
        ReportTypeResourceList<? extends ReportIndex> indices = dao.getIndices(TEST_STUDY, ReportType.PARTICIPANT);
        assertEquals(participantIndexCount+2, indices.getItems().size());

        boolean containsKey1 = false;
        boolean containsKey2 = false;
        for (ReportIndex oneIndex : indices.getItems()) {
            if (oneIndex.getIdentifier().equals(participantReportKey1.getIdentifier())) {
                containsKey1 = true;
            } else if (oneIndex.getIdentifier().equals(participantReportKey2.getIdentifier())) {
                containsKey2 = true;
            }
        }
        assertTrue(containsKey1);
        assertTrue(containsKey2);

        // Wrong type returns same count as before
        indices = dao.getIndices(TEST_STUDY, ReportType.STUDY);
        assertEquals(studyIndexCount, indices.getItems().size());
        
        dao.removeIndex(participantReportKey1);
        dao.removeIndex(participantReportKey2);
        
        indices = dao.getIndices(TEST_STUDY, ReportType.PARTICIPANT);
        assertEquals(participantIndexCount, indices.getItems().size());
    }
    
    @Test(expected = EntityNotFoundException.class)
    public void updateNonExistentIndex() {
        ReportIndex index = ReportIndex.create();
        index.setKey(studyReportKey1.getIndexKeyString());
        index.setIdentifier(studyReportKey1.getIdentifier());
        index.setPublic(true);
        
        dao.updateIndex(index);
    }
}
