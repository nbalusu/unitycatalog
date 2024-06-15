package io.unitycatalog.server.persist;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.model.*;
import io.unitycatalog.server.persist.converters.TableInfoConverter;
import io.unitycatalog.server.persist.dao.PropertyDAO;
import io.unitycatalog.server.persist.dao.TableInfoDAO;
import io.unitycatalog.server.utils.ValidationUtils;
import lombok.Getter;
import org.hibernate.query.Query;
import org.hibernate.type.StandardBasicTypes;
import io.unitycatalog.server.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

import java.util.*;

public class TableRepository {
    @Getter
    private static final TableRepository instance = new TableRepository();
    private static final Logger LOGGER = LoggerFactory.getLogger(TableRepository.class);
    private static final SessionFactory sessionFactory = HibernateUtil.getSessionFactory();
    private static final CatalogOperations catalogOperations = CatalogOperations.getInstance();
    private static final SchemaOperations schemaOperations = SchemaOperations.getInstance();

    private TableRepository() {}

    public TableInfo getTableById(String tableId) {
        LOGGER.debug("Getting table by id: " + tableId);
        try (Session session = sessionFactory.openSession()) {
            TableInfoDAO tableInfoDAO = session.get(TableInfoDAO.class, UUID.fromString(tableId));
            if (tableInfoDAO == null) {
                throw new BaseException(ErrorCode.NOT_FOUND, "Table not found: " + tableId);
            }

            TableInfo tableInfo = TableInfoConverter.convertToDTO(tableInfoDAO);
            tableInfo.setColumns(TableInfoConverter.convertColumnsToDTO(tableInfoDAO.getColumns()));
            tableInfo.setProperties(TableInfoConverter
                    .convertPropertiesToMap(findProperties(session, tableInfoDAO.getId())));
            return tableInfo;
        }
    }

    public TableInfo getTable(String fullName) {
        LOGGER.debug("Getting table: " + fullName);
        try (Session session = sessionFactory.openSession()) {
            String[] parts = fullName.split("\\.");
            if (parts.length != 3) {
                throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Invalid table name: " + fullName);
            }
            String catalogName = parts[0];
            String schemaName = parts[1];
            String tableName = parts[2];
            TableInfoDAO tableInfoDAO = findTable(session, catalogName, schemaName, tableName);
            if (tableInfoDAO == null) {
                throw new BaseException(ErrorCode.NOT_FOUND, "Table not found: " + fullName);
            }
            TableInfo tableInfo = TableInfoConverter.convertToDTO(tableInfoDAO);
            tableInfo.setColumns(TableInfoConverter.convertColumnsToDTO(tableInfoDAO.getColumns()));
            tableInfo.setProperties(TableInfoConverter
                    .convertPropertiesToMap(findProperties(session, tableInfoDAO.getId())));
            tableInfo.setCatalogName(catalogName);
            tableInfo.setSchemaName(schemaName);
            return tableInfo;
        }
    }

    private List<PropertyDAO> findProperties(Session session, UUID tableId) {
        LOGGER.debug("Getting properties for table: " + tableId);
        String hql = "FROM PropertyDAO p WHERE p.entityId = :tableId and p.entityType = 'table'";
        Query<PropertyDAO> query = session.createQuery(hql, PropertyDAO.class);
        query.setParameter("tableId", tableId);
        return query.list();
    }

    public String getTableUniformMetadataLocation(Session session,  String catalogName, String schemaName, String tableName) {
        TableInfoDAO dao = findTable(session, catalogName, schemaName, tableName);
        return dao.getUniformIcebergMetadataLocation();
    }

    private TableInfoDAO findTable(Session session, String catalogName, String schemaName, String tableName) {
        String schemaId = getSchemaId(session, catalogName, schemaName);
        return findBySchemaIdAndName(session, schemaId, tableName);
    }


    public TableInfo createTable(CreateTable createTable) {
        ValidationUtils.validateSqlObjectName(createTable.getName());
        TableInfo tableInfo = TableInfoConverter.convertFromCreateRequest(createTable);
        String fullName = getTableFullName(tableInfo);
        LOGGER.debug("Creating table: " + fullName);

        //Session session = null;
        Transaction tx = null;
        try (Session session = sessionFactory.openSession()) {
            String catalogName = tableInfo.getCatalogName();
            String schemaName = tableInfo.getSchemaName();
            String schemaId = getSchemaId(session, catalogName, schemaName);
            tx = session.beginTransaction();

            try {
                // Check if table already exists
                TableInfoDAO existingTable = findBySchemaIdAndName(session, schemaId, tableInfo.getName());
                if (existingTable != null) {
                    throw new BaseException(ErrorCode.ALREADY_EXISTS, "Table already exists: " + fullName);
                }
                if (TableType.MANAGED.equals(tableInfo.getTableType())) {
                    throw new BaseException(ErrorCode.INVALID_ARGUMENT, "MANAGED table creation is not supported yet.");
                }
                // assuming external table
                if (tableInfo.getStorageLocation() == null) {
                    throw new BaseException(ErrorCode.INVALID_ARGUMENT,
                            "Storage location is required for external table");
                }
                TableInfoDAO tableInfoDAO = TableInfoConverter.convertToDAO(tableInfo);
                tableInfoDAO.setSchemaId(UUID.fromString(schemaId));
                String tableId = UUID.randomUUID().toString();
                // set id
                tableInfoDAO.setId(UUID.fromString(tableId));
                // set table id in return object
                tableInfo.setTableId(tableId);
                // set created and updated time in return object
                tableInfo.setCreatedAt(tableInfoDAO.getCreatedAt().getTime());
                tableInfo.setUpdatedAt(tableInfoDAO.getUpdatedAt().getTime());
                // create columns
                tableInfoDAO.setColumns(TableInfoConverter.convertColumnsListToDAO(tableInfo, tableInfoDAO));
                // create properties
                TableInfoConverter.convertPropertiesToDAOList(tableInfo, tableId).forEach(session::persist);
                // finally create the table
                session.persist(tableInfoDAO);

                tx.commit();
            } catch (RuntimeException e) {
                if (tx != null && tx.getStatus().canRollback()) {
                    tx.rollback();
                }
                throw e;
            }
        } catch (RuntimeException e) {
            if (e instanceof BaseException) {
                throw e;
            }
            throw new BaseException(ErrorCode.INTERNAL, "Error creating table: " + fullName, e);
        }
        return tableInfo;
    }

    public TableInfoDAO findBySchemaIdAndName(Session session, String schemaId, String name) {
        String hql = "FROM TableInfoDAO t WHERE t.schemaId = :schemaId AND t.name = :name";
        Query<TableInfoDAO> query = session.createQuery(hql, TableInfoDAO.class);
        query.setParameter("schemaId", UUID.fromString(schemaId));
        query.setParameter("name", name);
        LOGGER.debug("Finding table by schemaId: " + schemaId + " and name: " + name);
        return query.uniqueResult();  // Returns null if no result is found
    }

    private String getTableFullName(TableInfo tableInfo) {
        return tableInfo.getCatalogName() + "." + tableInfo.getSchemaName() + "." + tableInfo.getName();
    }

    public String getSchemaId(Session session, String catalogName, String schemaName) {

        CatalogInfo catalogInfoDAO = catalogOperations.getCatalog(session, catalogName);
        if (catalogInfoDAO == null) {
            throw new BaseException(ErrorCode.NOT_FOUND, "Catalog not found: " + catalogName);
        }
        SchemaInfo schemaInfo = schemaOperations.getSchema(session, catalogName
                + "." + schemaName);
        if (schemaInfo == null) {
            throw new BaseException(ErrorCode.NOT_FOUND, "Schema not found: " + schemaName);
        }
        return schemaInfo.getSchemaId();

    }

    public static Date convertMillisToDate(String millisString) {
        if (millisString == null || millisString.isEmpty()) {
            return null;
        }
        try {
            long millis = Long.parseLong(millisString);
            return new Date(millis);
        } catch (NumberFormatException e) {
            throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Unable to interpret page token: " + millisString);
        }
    }

    public static String getNextPageToken(List<TableInfoDAO> tables) {
        if (tables == null || tables.isEmpty()) {
            return "";
        }
        // Assuming the last item in the list is the least recent based on the query
        return String.valueOf(tables.get(tables.size() - 1).getUpdatedAt().getTime());
    }

    /**
     * Return the most recently updated tables first in descending order of updated time
     * @param catalogName
     * @param schemaName
     * @param maxResults
     * @param nextPageToken
     * @param omitProperties
     * @param omitColumns
     * @return
     */
    public ListTablesResponse listTables(String catalogName,
                                      String schemaName,
                                      Integer maxResults,
                                      String nextPageToken,
                                      Boolean omitProperties,
                                      Boolean omitColumns) {
        List<TableInfo> result = new ArrayList<>();
        String returnNextPageToken = null;

        try (Session session = sessionFactory.openSession()) {
            String schemaId = getSchemaId(session, catalogName, schemaName);
            System.out.println("### schemaId " + schemaId);
            System.out.println("### pageToken " + convertMillisToDate(nextPageToken));
            Query<TableInfoDAO> query = null;
            if (nextPageToken != null) {
                String hql = "FROM TableInfoDAO t WHERE t.schemaId = :schemaId and " +
                        "(t.updatedAt < :pageToken OR :pageToken is null) order by t.updatedAt desc";
                query = session.createQuery(hql, TableInfoDAO.class);
                query.setParameter("schemaId", UUID.fromString(schemaId));
                query.setParameter("pageToken", convertMillisToDate(nextPageToken));
            } else {
                String hql = "FROM TableInfoDAO t WHERE t.schemaId = :schemaId order by t.updatedAt desc";
                query = session.createQuery(hql, TableInfoDAO.class);
                query.setParameter("schemaId", UUID.fromString(schemaId));
            }
            query.setMaxResults(maxResults);
            List<TableInfoDAO> tableInfoDAOList = query.list();

            returnNextPageToken = getNextPageToken(tableInfoDAOList);

            for (TableInfoDAO tableInfoDAO : tableInfoDAOList) {
                TableInfo tableInfo = TableInfoConverter.convertToDTO(tableInfoDAO);
                if (!omitColumns) {
                    tableInfo.setColumns(TableInfoConverter.convertColumnsToDTO(tableInfoDAO.getColumns()));
                }
                if (!omitProperties) {
                    tableInfo.setProperties(TableInfoConverter.convertPropertiesToMap(
                            findProperties(session, tableInfoDAO.getId())));
                }
                tableInfo.setCatalogName(catalogName);
                tableInfo.setSchemaName(schemaName);
                result.add(tableInfo);
            }
        }
        return new ListTablesResponse().tables(result).nextPageToken(returnNextPageToken);
    }

    public void deleteTable(String fullName) {

        try (Session session = sessionFactory.openSession()) {
            String[] parts = fullName.split("\\.");
            if (parts.length != 3) {
                throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Invalid table name: " + fullName);
            }
            String catalogName = parts[0];
            String schemaName = parts[1];
            String tableName = parts[2];

            String schemaId = getSchemaId(session, catalogName, schemaName);

            TableInfoDAO tableInfoDAO = findBySchemaIdAndName(session, schemaId, tableName);
            if (tableInfoDAO == null) {
                throw new BaseException(ErrorCode.NOT_FOUND, "Table not found: " + fullName);
            }
            Transaction tx = session.beginTransaction();
            try {
                if (TableType.MANAGED.getValue().equals(tableInfoDAO.getType())) {
                    FileUtils.deleteDirectory(tableInfoDAO.getUrl());
                }
            } catch (Throwable e) {
                LOGGER.error("Error deleting table directory: " + tableInfoDAO.getUrl());
            }
            try {
                findProperties(session, tableInfoDAO.getId()).forEach(session::remove);
                session.remove(tableInfoDAO);
                tx.commit();
            } catch (RuntimeException e) {
                if (tx != null && tx.getStatus().canRollback()) {
                    tx.rollback();
                }
                throw e;
            }
        }

    }
}
