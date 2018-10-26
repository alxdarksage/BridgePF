package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkNotNull;

import java.nio.charset.Charset;

import javax.annotation.Resource;

import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.services.sns.model.CheckIfPhoneNumberIsOptedOutRequest;
import com.amazonaws.services.sns.model.CheckIfPhoneNumberIsOptedOutResult;
import com.amazonaws.services.sns.model.OptInPhoneNumberRequest;
import com.amazonaws.services.sns.model.PublishResult;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.dao.SmsMessageDao;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.models.accounts.Phone;
import org.sagebionetworks.bridge.models.sms.SmsMessage;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.sms.SmsMessageProvider;
import org.sagebionetworks.bridge.time.DateUtils;
import org.sagebionetworks.bridge.validators.SmsMessageValidator;
import org.sagebionetworks.bridge.validators.Validate;

/** Service for handling SMS metadata (opt-outs, message logging) and for handling webhooks for receiving SMS. */
@Component
public class SmsService {
    private static final Logger LOG = LoggerFactory.getLogger(SmsService.class);

    // mPower 2.0 study burst notifications can be fairly long. The longest one has 230 chars of fixed content, an app
    // url that's 53 characters long, and some freeform text that can be potentially 255 characters long, for a total
    // of 538 characters. Round to a nice round 600 characters (about 4.5 SMS messages, if broken up).
    private static final int SMS_CHARACTER_LIMIT = 600;

    private SmsMessageDao messageDao;
    private AmazonSNSClient snsClient;

    /** Message DAO, for writing to and reading from the SMS message log. */
    @Autowired
    public final void setMessageDao(SmsMessageDao messageDao) {
        this.messageDao = messageDao;
    }

    /** SNS client, to send SMS through AWS. */
    @Resource(name = "snsClient")
    public final void setSnsClient(AmazonSNSClient snsClient) {
        this.snsClient = snsClient;
    }

    /** Sends an SMS message using the given message provider. */
    public void sendSmsMessage(SmsMessageProvider provider) {
        checkNotNull(provider);
        StudyIdentifier studyId = provider.getStudy().getStudyIdentifier();
        Phone recipientPhone = provider.getPhone();
        String message = provider.getFormattedMessage();

        // Check max SMS length.
        if (message.getBytes(Charset.forName("US-ASCII")).length > SMS_CHARACTER_LIMIT) {
            throw new BridgeServiceException("SMS message cannot be longer than 600 UTF-8/ASCII characters.");
        }

        // Send SMS.
        String messageId;
        PublishResult result = snsClient.publish(provider.getSmsRequest());
        messageId = result.getMessageId();

        // Log SMS message.
        SmsMessage smsMessage = SmsMessage.create();
        smsMessage.setPhoneNumber(recipientPhone.getNumber());
        smsMessage.setSentOn(DateUtils.getCurrentMillisFromEpoch());
        smsMessage.setMessageBody(message);
        smsMessage.setMessageId(messageId);
        smsMessage.setSmsType(provider.getSmsTypeEnum());
        smsMessage.setStudyId(studyId.getIdentifier());
        logMessage(smsMessage);

        LOG.debug("Sent SMS message, study=" + studyId.getIdentifier() + ", message ID=" + messageId);
    }

    /** Gets the message we most recently sent to the given phone number. */
    public SmsMessage getMostRecentMessage(String number) {
        if (StringUtils.isBlank(number)) {
            throw new BadRequestException("number is required");
        }
        return messageDao.getMostRecentMessage(number);
    }

    /** Saves an SMS message that we sent to the SMS message log. */
    public void logMessage(SmsMessage message) {
        if (message == null) {
            throw new BadRequestException("message is required");
        }
        Validate.entityThrowingException(SmsMessageValidator.INSTANCE, message);
        messageDao.logMessage(message);
    }

    /**
     * Opt a phone number back in if it is opted out. This is only used when a new account is created, generally in
     * a new study. User ID is used for logging.
     */
    public void optInPhoneNumber(String userId, Phone phone) {
        if (StringUtils.isBlank(userId)) {
            throw new BadRequestException("userId is required");
        }
        if (phone == null) {
            throw new BadRequestException("phone is required");
        }
        if (!Phone.isValid(phone)) {
            throw new BadRequestException("phone is invalid");
        }

        // Check if phone number is opted out.
        CheckIfPhoneNumberIsOptedOutRequest checkRequest = new CheckIfPhoneNumberIsOptedOutRequest()
                .withPhoneNumber(phone.getNumber());
        CheckIfPhoneNumberIsOptedOutResult checkResult = snsClient.checkIfPhoneNumberIsOptedOut(checkRequest);

        if (Boolean.TRUE.equals(checkResult.isOptedOut())) {
            LOG.info("Opting in user " + userId + " for SMS messages");

            // User was previously opted out. They created a new account (almost certainly in a new study). We need
            // to opt them back in. Note that according to AWS, this can only be done once every 30 days to prevent
            // abuse.
            OptInPhoneNumberRequest optInRequest = new OptInPhoneNumberRequest().withPhoneNumber(
                    phone.getNumber());
            snsClient.optInPhoneNumber(optInRequest);
        }
    }
}