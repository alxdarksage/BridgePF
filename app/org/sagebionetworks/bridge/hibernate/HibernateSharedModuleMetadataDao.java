package org.sagebionetworks.bridge.hibernate;

import java.util.List;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.dao.SharedModuleMetadataDao;
import org.sagebionetworks.bridge.models.sharedmodules.SharedModuleMetadata;

/** Implementation of SharedModuleMetadataDao, using Hibernate backed by a SQL database. */
@Component
public class HibernateSharedModuleMetadataDao implements SharedModuleMetadataDao {
    private SessionFactory hibernateSessionFactory;

    /** Hibernate session factory, used to talk to SQL. Configured via Spring. */
    @Autowired
    public final void setHibernateSessionFactory(SessionFactory hibernateSessionFactory) {
        this.hibernateSessionFactory = hibernateSessionFactory;
    }

    /** {@inheritDoc} */
    @Override
    public SharedModuleMetadata createMetadata(SharedModuleMetadata metadata) {
        return sessionHelper(session -> {
            session.save(metadata);
            return metadata;
        });
    }

    /** {@inheritDoc} */
    @Override
    public void deleteMetadataByIdAllVersions(String id) {
        sessionHelper(session -> {
            session.createQuery("delete from HibernateSharedModuleMetadata where id='" + id + "'").executeUpdate();
            return null;
        });
    }

    /** {@inheritDoc} */
    @Override
    public void deleteMetadataByIdAndVersion(String id, int version) {
        sessionHelper(session -> {
            session.createQuery("delete from HibernateSharedModuleMetadata where id='" + id + "' and version=" +
                    version).executeUpdate();
            return null;
        });
    }

    /** {@inheritDoc} */
    @Override
    public List<SharedModuleMetadata> getAllMetadataAllVersions() {
        return sessionHelper(session -> session.createQuery("from HibernateSharedModuleMetadata",
                SharedModuleMetadata.class).list());
    }

    /** {@inheritDoc} */
    @Override
    public List<SharedModuleMetadata> getMetadataByIdAllVersions(String id) {
        return sessionHelper(session -> session.createQuery("from HibernateSharedModuleMetadata where id='" + id + "'",
                SharedModuleMetadata.class).list());
    }

    /** {@inheritDoc} */
    @Override
    public SharedModuleMetadata getMetadataByIdAndVersion(String id, int version) {
        return sessionHelper(session -> session.get(HibernateSharedModuleMetadata.class,
                new HibernateSharedModuleMetadataKey(id, version)));
    }

    /** {@inheritDoc} */
    @Override
    public SharedModuleMetadata getMetadataByIdLatestVersion(String id) {
        return sessionHelper(session -> {
            List<SharedModuleMetadata> metadataList = session.createQuery(
                    "from HibernateSharedModuleMetadata where id='" + id + "' order by version desc",
                    SharedModuleMetadata.class).setMaxResults(1).list();
            if (metadataList.isEmpty()) {
                return null;
            } else {
                return metadataList.get(0);
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    public List<SharedModuleMetadata> queryMetadata(String whereClause) {
        // build query
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("from HibernateSharedModuleMetadata");
        if (StringUtils.isNotBlank(whereClause)) {
            queryBuilder.append(" where ").append(whereClause);
        }

        // execute query
        return sessionHelper(session -> session.createQuery(queryBuilder.toString(), SharedModuleMetadata.class)
                .list());
    }

    /** {@inheritDoc} */
    @Override
    public SharedModuleMetadata updateMetadata(SharedModuleMetadata metadata) {
        return sessionHelper(session -> {
            session.update(metadata);
            return metadata;
        });
    }

    // Helper function, which handles opening and closing sessions and transactions.
    private <T> T sessionHelper(Function<Session, T> function) {
        T retval;
        try (Session session = hibernateSessionFactory.openSession()) {
            Transaction transaction = session.beginTransaction();
            retval = function.apply(session);
            transaction.commit();
        }
        return retval;
    }
}
