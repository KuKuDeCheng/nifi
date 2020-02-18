/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.standard;

import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.FragmentAttributes;
import org.apache.nifi.processor.AbstractSessionFactoryProcessor;
import org.apache.nifi.processor.FlowFileFilter;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessSessionFactory;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processor.util.pattern.ErrorTypes;
import org.apache.nifi.processor.util.pattern.ExceptionHandler;
import org.apache.nifi.processor.util.pattern.PartialFunctions;
import org.apache.nifi.processor.util.pattern.PartialFunctions.FetchFlowFiles;
import org.apache.nifi.processor.util.pattern.PartialFunctions.FlowFileGroup;
import org.apache.nifi.processor.util.pattern.PutGroup;
import org.apache.nifi.processor.util.pattern.RollbackOnFailure;
import org.apache.nifi.processor.util.pattern.RoutingResult;
import org.apache.nifi.stream.io.StreamUtils;
import org.apache.nifi.util.db.JdbcCommon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static java.lang.String.format;
import static org.apache.nifi.processor.util.pattern.ExceptionHandler.createOnError;

@SupportsBatching
@SeeAlso(ConvertJSONToSQL.class)
@InputRequirement(Requirement.INPUT_REQUIRED)
@Tags({"sql", "put", "rdbms", "database", "update", "insert", "relational"})
@CapabilityDescription("Executes a SQL UPDATE or INSERT command. The content of an incoming FlowFile is expected to be the SQL command "
        + "to execute. The SQL command may use the ? to escape parameters. In this case, the parameters to use must exist as FlowFile attributes "
        + "with the naming convention sql.args.N.type and sql.args.N.value, where N is a positive integer. The sql.args.N.type is expected to be "
        + "a number indicating the JDBC Type. The content of the FlowFile is expected to be in UTF-8 format.")
@ReadsAttributes({
        @ReadsAttribute(attribute = "fragment.identifier", description = "If the <Support Fragment Transactions> property is true, this attribute is used to determine whether or "
                + "not two FlowFiles belong to the same transaction."),
        @ReadsAttribute(attribute = "fragment.count", description = "If the <Support Fragment Transactions> property is true, this attribute is used to determine how many FlowFiles "
                + "are needed to complete the transaction."),
        @ReadsAttribute(attribute = "fragment.index", description = "If the <Support Fragment Transactions> property is true, this attribute is used to determine the order that the FlowFiles "
                + "in a transaction should be evaluated."),
        @ReadsAttribute(attribute = "sql.args.N.type", description = "Incoming FlowFiles are expected to be parametrized SQL statements. The type of each Parameter is specified as an integer "
                + "that represents the JDBC Type of the parameter."),
        @ReadsAttribute(attribute = "sql.args.N.value", description = "Incoming FlowFiles are expected to be parametrized SQL statements. The value of the Parameters are specified as "
                + "sql.args.1.value, sql.args.2.value, sql.args.3.value, and so on. The type of the sql.args.1.value Parameter is specified by the sql.args.1.type attribute."),
        @ReadsAttribute(attribute = "sql.args.N.format", description = "This attribute is always optional, but default options may not always work for your data. "
                + "Incoming FlowFiles are expected to be parametrized SQL statements. In some cases "
                + "a format option needs to be specified, currently this is only applicable for binary data types, dates, times and timestamps. Binary Data Types (defaults to 'ascii') - "
                + "ascii: each string character in your attribute value represents a single byte. This is the format provided by Avro Processors. "
                + "base64: the string is a Base64 encoded string that can be decoded to bytes. "
                + "hex: the string is hex encoded with all letters in upper case and no '0x' at the beginning. "
                + "Dates/Times/Timestamps - "
                + "Date, Time and Timestamp formats all support both custom formats or named format ('yyyy-MM-dd','ISO_OFFSET_DATE_TIME') "
                + "as specified according to java.time.format.DateTimeFormatter. "
                + "If not specified, a long value input is expected to be an unix epoch (milli seconds from 1970/1/1), or a string value in "
                + "'yyyy-MM-dd' format for Date, 'HH:mm:ss.SSS' for Time (some database engines e.g. Derby or MySQL do not support milliseconds and will truncate milliseconds), "
                + "'yyyy-MM-dd HH:mm:ss.SSS' for Timestamp is used.")
})
@WritesAttributes({
        @WritesAttribute(attribute = "sql.generated.key", description = "If the database generated a key for an INSERT statement and the Obtain Generated Keys property is set to true, "
                + "this attribute will be added to indicate the generated key, if possible. This feature is not supported by all database vendors.")
})
public class PutSQL extends AbstractSessionFactoryProcessor {

    static final PropertyDescriptor CONNECTION_POOL = new PropertyDescriptor.Builder()
            .name("JDBC Connection Pool")
            .description("Specifies the JDBC Connection Pool to use in order to convert the JSON message to a SQL statement. "
                    + "The Connection Pool is necessary in order to determine the appropriate database column types.")
            .identifiesControllerService(DBCPService.class)
            .required(true)
            .build();

    static final PropertyDescriptor SQL_STATEMENT = new PropertyDescriptor.Builder()
            .name("putsql-sql-statement")
            .displayName("SQL Statement")
            .description("The SQL statement to execute. The statement can be empty, a constant value, or built from attributes "
                    + "using Expression Language. If this property is specified, it will be used regardless of the content of "
                    + "incoming flowfiles. If this property is empty, the content of the incoming flow file is expected "
                    + "to contain a valid SQL statement, to be issued by the processor to the database.")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    static final PropertyDescriptor AUTO_COMMIT = new PropertyDescriptor.Builder()
            .name("database-session-autocommit")
            .displayName("Database Session AutoCommit")
            .description("The autocommit mode to set on the database connection being used.")
            .allowableValues("true", "false")
            .defaultValue("false")
            .build();

    static final PropertyDescriptor SUPPORT_TRANSACTIONS = new PropertyDescriptor.Builder()
            .name("Support Fragmented Transactions")
            .description("If true, when a FlowFile is consumed by this Processor, the Processor will first check the fragment.identifier and fragment.count attributes of that FlowFile. "
                    + "If the fragment.count value is greater than 1, the Processor will not process any FlowFile with that fragment.identifier until all are available; "
                    + "at that point, it will process all FlowFiles with that fragment.identifier as a single transaction, in the order specified by the FlowFiles' fragment.index attributes. "
                    + "This Provides atomicity of those SQL statements. If this value is false, these attributes will be ignored and the updates will occur independent of one another.")
            .allowableValues("true", "false")
            .defaultValue("true")
            .build();
    static final PropertyDescriptor SUPPORT_TRANSACTION_ROLLBACK = new PropertyDescriptor.Builder()
            .name("Support Database Transaction RollBack")
            .description("If the <Support Fragmented Transactions> property is set to true and the <Rollback On Failure> is set to false, specifies that whether the processor support database transaction rollback." +
                    "If false, the  database transaction will not rollback when some SQL failed. If this value is true, the database transaction will rollback when some SQL failed. If the <Rollback On Failure> is set to true," +
                    "this property will be ignored")
            .allowableValues("true", "false")
            .defaultValue("true")
            .build();
    static final PropertyDescriptor TRANSACTION_TIMEOUT = new PropertyDescriptor.Builder()
            .name("Transaction Timeout")
            .description("If the <Support Fragmented Transactions> property is set to true, specifies how long to wait for all FlowFiles for a particular fragment.identifier attribute "
                    + "to arrive before just transferring all of the FlowFiles with that identifier to the 'failure' relationship")
            .required(false)
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();
    static final PropertyDescriptor BATCH_SIZE = new PropertyDescriptor.Builder()
            .name("Batch Size")
            .description("The preferred number of FlowFiles to put to the database in a single transaction")
            .required(true)
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .defaultValue("100")
            .build();
    static final PropertyDescriptor OBTAIN_GENERATED_KEYS = new PropertyDescriptor.Builder()
            .name("Obtain Generated Keys")
            .description("If true, any key that is automatically generated by the database will be added to the FlowFile that generated it using the sql.generate.key attribute. "
                    + "This may result in slightly slower performance and is not supported by all databases.")
            .allowableValues("true", "false")
            .defaultValue("false")
            .build();

    static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("A FlowFile is routed to this relationship after the database is successfully updated")
            .build();
    static final Relationship REL_RETRY = new Relationship.Builder()
            .name("retry")
            .description("A FlowFile is routed to this relationship if the database cannot be updated but attempting the operation again may succeed")
            .build();
    static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("A FlowFile is routed to this relationship if the database cannot be updated and retrying the operation will also fail, "
                    + "such as an invalid query or an integrity constraint violation")
            .build();

    private static final String FRAGMENT_ID_ATTR = FragmentAttributes.FRAGMENT_ID.key();
    private static final String FRAGMENT_INDEX_ATTR = FragmentAttributes.FRAGMENT_INDEX.key();
    private static final String FRAGMENT_COUNT_ATTR = FragmentAttributes.FRAGMENT_COUNT.key();

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(CONNECTION_POOL);
        properties.add(SQL_STATEMENT);
        properties.add(SUPPORT_TRANSACTIONS);
        properties.add(SUPPORT_TRANSACTION_ROLLBACK);
        properties.add(AUTO_COMMIT);
        properties.add(TRANSACTION_TIMEOUT);
        properties.add(BATCH_SIZE);
        properties.add(OBTAIN_GENERATED_KEYS);
        properties.add(RollbackOnFailure.ROLLBACK_ON_FAILURE);
        return properties;
    }

    @Override
    protected final Collection<ValidationResult> customValidate(ValidationContext context) {
        final Collection<ValidationResult> results = new ArrayList<>();
        final String support_transactions = context.getProperty(SUPPORT_TRANSACTIONS).getValue();
        final String rollback_on_failure = context.getProperty(RollbackOnFailure.ROLLBACK_ON_FAILURE).getValue();
        final String auto_commit = context.getProperty(AUTO_COMMIT).getValue();
        final String support_database_rollback = context.getProperty(SUPPORT_TRANSACTION_ROLLBACK).getValue();

        if(auto_commit.equalsIgnoreCase("true")) {
            if(support_transactions.equalsIgnoreCase("true")) {
                results.add(new ValidationResult.Builder()
                                .subject(SUPPORT_TRANSACTIONS.getDisplayName())
                                .explanation(format("'%s' cannot be set to 'true' when '%s' is also set to 'true'."
                                        + "Transactions for batch updates cannot be supported when auto commit is set to 'true'",
                                        SUPPORT_TRANSACTIONS.getDisplayName(), AUTO_COMMIT.getDisplayName()))
                                .build());
            }
            if(support_database_rollback.equalsIgnoreCase("true")) {
                results.add(new ValidationResult.Builder()
                        .subject(SUPPORT_TRANSACTION_ROLLBACK.getDisplayName())
                        .explanation(format("'%s' cannot be set to 'true' when '%s' is also set to 'true'."
                                        + "Transactions for batch updates cannot be supported when auto commit is set to 'true'",
                                SUPPORT_TRANSACTION_ROLLBACK.getDisplayName(), AUTO_COMMIT.getDisplayName()))
                        .build());
            }
            if(rollback_on_failure.equalsIgnoreCase("true")) {
                results.add(new ValidationResult.Builder()
                        .subject(RollbackOnFailure.ROLLBACK_ON_FAILURE.getDisplayName())
                        .explanation(format("'%s' cannot be set to 'true' when '%s' is also set to 'true'."
                                + "Transaction rollbacks for batch updates cannot be supported when auto commit is set to 'true'",
                                RollbackOnFailure.ROLLBACK_ON_FAILURE.getDisplayName(), AUTO_COMMIT.getDisplayName()))
                        .build());
            }
        }
        return results;
    }

    @Override
    public Set<Relationship> getRelationships() {
        final Set<Relationship> rels = new HashSet<>();
        rels.add(REL_SUCCESS);
        rels.add(REL_RETRY);
        rels.add(REL_FAILURE);
        return rels;
    }

    private static class FunctionContext extends RollbackOnFailure {
        private boolean obtainKeys = false;
        private boolean fragmentedTransaction = false;
        private boolean originalAutoCommit = false;
        private final long startNanos = System.nanoTime();

        private FunctionContext(boolean rollbackOnFailure,boolean databaseTransactionRollBack) {
            super(rollbackOnFailure, true,databaseTransactionRollBack);
        }

        private boolean isSupportBatching() {
            return !obtainKeys && !fragmentedTransaction;
        }
    }

    private PutGroup<FunctionContext, Connection, StatementFlowFileEnclosure> process;
    private BiFunction<FunctionContext, ErrorTypes, ErrorTypes.Result> adjustError;
    private ExceptionHandler<FunctionContext> exceptionHandler;


    private final FetchFlowFiles<FunctionContext> fetchFlowFiles = (c, s, fc, r) -> {
        final FlowFilePoll poll = pollFlowFiles(c, s, fc, r);
        if (poll == null) {
            return null;
        }
        fc.fragmentedTransaction = poll.isFragmentedTransaction();
        return poll.getFlowFiles();
    };

    private final PartialFunctions.InitConnection<FunctionContext, Connection> initConnection = (c, s, fc, ffs) -> {
        final Connection connection = c.getProperty(CONNECTION_POOL).asControllerService(DBCPService.class)
                .getConnection(ffs == null || ffs.isEmpty() ? Collections.emptyMap() : ffs.get(0).getAttributes());
        try {
            fc.originalAutoCommit = connection.getAutoCommit();
            final boolean autocommit = c.getProperty(AUTO_COMMIT).asBoolean();
            if(fc.originalAutoCommit != autocommit) {
                connection.setAutoCommit(autocommit);
            }
        } catch (SQLException e) {
            throw new ProcessException("Failed to disable auto commit due to " + e, e);
        }
        return connection;
    };


    @FunctionalInterface
    private interface GroupingFunction {
        void apply(final ProcessContext context, final ProcessSession session, final FunctionContext fc,
                   final Connection conn, final List<FlowFile> flowFiles,
                   final List<StatementFlowFileEnclosure> groups,
                   final Map<String, StatementFlowFileEnclosure> sqlToEnclosure,
                   final RoutingResult result);
    }

    private GroupingFunction groupFragmentedTransaction = (context, session, fc, conn, flowFiles, groups, sqlToEnclosure, result) -> {
        final FragmentedEnclosure fragmentedEnclosure = new FragmentedEnclosure();
        groups.add(fragmentedEnclosure);

        for (final FlowFile flowFile : flowFiles) {
            final String sql = context.getProperty(PutSQL.SQL_STATEMENT).isSet()
                    ? context.getProperty(PutSQL.SQL_STATEMENT).evaluateAttributeExpressions(flowFile).getValue()
                    : getSQL(session, flowFile);

            final StatementFlowFileEnclosure enclosure = sqlToEnclosure
                    .computeIfAbsent(sql, k -> new StatementFlowFileEnclosure(sql));

            fragmentedEnclosure.addFlowFile(flowFile, enclosure);
        }
    };

    private final GroupingFunction groupFlowFilesBySQLBatch = (context, session, fc, conn, flowFiles, groups, sqlToEnclosure, result) -> {
        for (final FlowFile flowFile : flowFiles) {
            final String sql = context.getProperty(PutSQL.SQL_STATEMENT).isSet()
                    ? context.getProperty(PutSQL.SQL_STATEMENT).evaluateAttributeExpressions(flowFile).getValue()
                    : getSQL(session, flowFile);

            // Get or create the appropriate PreparedStatement to use.
            final StatementFlowFileEnclosure enclosure = sqlToEnclosure
                    .computeIfAbsent(sql, k -> {
                        final StatementFlowFileEnclosure newEnclosure = new StatementFlowFileEnclosure(sql);
                        groups.add(newEnclosure);
                        return newEnclosure;
                    });

            if(!exceptionHandler.execute(fc, flowFile, input -> {
                final PreparedStatement stmt = enclosure.getCachedStatement(conn);
                JdbcCommon.setParameters(stmt, flowFile.getAttributes());
                stmt.addBatch();
            }, onFlowFileError(context, session, result))) {
                continue;
            }

            enclosure.addFlowFile(flowFile);
        }
    };

    private GroupingFunction groupFlowFilesBySQL = (context, session, fc, conn, flowFiles, groups, sqlToEnclosure, result) -> {
        for (final FlowFile flowFile : flowFiles) {
            final String sql = context.getProperty(PutSQL.SQL_STATEMENT).isSet()
                    ? context.getProperty(PutSQL.SQL_STATEMENT).evaluateAttributeExpressions(flowFile).getValue()
                    : getSQL(session, flowFile);

            // Get or create the appropriate PreparedStatement to use.
            final StatementFlowFileEnclosure enclosure = sqlToEnclosure
                    .computeIfAbsent(sql, k -> {
                        final StatementFlowFileEnclosure newEnclosure = new StatementFlowFileEnclosure(sql);
                        groups.add(newEnclosure);
                        return newEnclosure;
                    });

            enclosure.addFlowFile(flowFile);
        }
    };

    final PutGroup.GroupFlowFiles<FunctionContext, Connection, StatementFlowFileEnclosure> groupFlowFiles = (context, session, fc, conn, flowFiles, result) -> {
        final Map<String, StatementFlowFileEnclosure> sqlToEnclosure = new HashMap<>();
        final List<StatementFlowFileEnclosure> groups = new ArrayList<>();

        // There are three patterns:
        // 1. Support batching: An enclosure has multiple FlowFiles being executed in a batch operation
        // 2. Obtain keys: An enclosure has multiple FlowFiles, and each FlowFile is executed separately
        // 3. Fragmented transaction: One FlowFile per Enclosure?
        if (fc.obtainKeys) {
            groupFlowFilesBySQL.apply(context, session, fc, conn, flowFiles, groups, sqlToEnclosure, result);
        } else if (fc.fragmentedTransaction) {
            groupFragmentedTransaction.apply(context, session, fc, conn, flowFiles, groups, sqlToEnclosure, result);
        } else {
            groupFlowFilesBySQLBatch.apply(context, session, fc, conn, flowFiles, groups, sqlToEnclosure, result);
        }

        return groups;
    };

    final PutGroup.PutFlowFiles<FunctionContext, Connection, StatementFlowFileEnclosure> putFlowFiles = (context, session, fc, conn, enclosure, result) -> {

        if (fc.isSupportBatching()) {

            // We have PreparedStatement that have batches added to them.
            // We need to execute each batch and close the PreparedStatement.
            exceptionHandler.execute(fc, enclosure, input -> {
                try (final PreparedStatement stmt = enclosure.getCachedStatement(conn)) {
                    stmt.executeBatch();
                    result.routeTo(enclosure.getFlowFiles(), REL_SUCCESS);
                }
            }, onBatchUpdateError(context, session, result));

        } else {
            for (final FlowFile flowFile : enclosure.getFlowFiles()) {

                final StatementFlowFileEnclosure targetEnclosure
                        = enclosure instanceof FragmentedEnclosure
                        ? ((FragmentedEnclosure) enclosure).getTargetEnclosure(flowFile)
                        : enclosure;

                // Execute update one by one.
                exceptionHandler.execute(fc, flowFile, input -> {
                    try (final PreparedStatement stmt = targetEnclosure.getNewStatement(conn, fc.obtainKeys)) {

                        // set the appropriate parameters on the statement.
                        JdbcCommon.setParameters(stmt, flowFile.getAttributes());

                        stmt.executeUpdate();

                        // attempt to determine the key that was generated, if any. This is not supported by all
                        // database vendors, so if we cannot determine the generated key (or if the statement is not an INSERT),
                        // we will just move on without setting the attribute.
                        FlowFile sentFlowFile = flowFile;
                        final String generatedKey = determineGeneratedKey(stmt);
                        if (generatedKey != null) {
                            sentFlowFile = session.putAttribute(sentFlowFile, "sql.generated.key", generatedKey);
                        }

                        result.routeTo(sentFlowFile, REL_SUCCESS);

                    }
                }, onFlowFileError(context, session, result));
            }
        }

        if (result.contains(REL_SUCCESS)) {
            // Determine the database URL
            String url = "jdbc://unknown-host";
            try {
                url = conn.getMetaData().getURL();
            } catch (final SQLException sqle) {
            }

            // Emit a Provenance SEND event
            final long transmissionMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - fc.startNanos);
            for (final FlowFile flowFile : result.getRoutedFlowFiles().get(REL_SUCCESS)) {
                session.getProvenanceReporter().send(flowFile, url, transmissionMillis, true);
            }
        }
    };

    private ExceptionHandler.OnError<FunctionContext, FlowFile> onFlowFileError(final ProcessContext context, final ProcessSession session, final RoutingResult result) {
        ExceptionHandler.OnError<FunctionContext, FlowFile> onFlowFileError = createOnError(context, session, result, REL_FAILURE, REL_RETRY);
        onFlowFileError = onFlowFileError.andThen((c, i, r, e) -> {
            switch (r.destination()) {
                case Failure:
                    getLogger().error("Failed to update database for {} due to {}; routing to failure", new Object[] {i, e}, e);
                    break;
                case Retry:
                    getLogger().error("Failed to update database for {} due to {}; it is possible that retrying the operation will succeed, so routing to retry",
                            new Object[] {i, e}, e);
                    break;
                case Self:
                    getLogger().error("Failed to update database for {} due to {};", new Object[] {i, e}, e);
                    break;
            }
        });
        return RollbackOnFailure.createOnError(onFlowFileError);
    }

    private ExceptionHandler.OnError<FunctionContext, StatementFlowFileEnclosure> onBatchUpdateError(
            final ProcessContext context, final ProcessSession session, final RoutingResult result) {
        return RollbackOnFailure.createOnError((c, enclosure, r, e) -> {

            // If rollbackOnFailure is enabled, the error will be thrown as ProcessException instead.
            if (e instanceof BatchUpdateException && !c.isRollbackOnFailure()) {

                // If we get a BatchUpdateException, then we want to determine which FlowFile caused the failure,
                // and route that FlowFile to failure while routing those that finished processing to success and those
                // that have not yet been executed to retry.
                // Currently fragmented transaction does not use batch update.
                final int[] updateCounts = ((BatchUpdateException) e).getUpdateCounts();
                final List<FlowFile> batchFlowFiles = enclosure.getFlowFiles();

                // In the presence of a BatchUpdateException, the driver has the option of either stopping when an error
                // occurs, or continuing. If it continues, then it must account for all statements in the batch and for
                // those that fail return a Statement.EXECUTE_FAILED for the number of rows updated.
                // So we will iterate over all of the update counts returned. If any is equal to Statement.EXECUTE_FAILED,
                // we will route the corresponding FlowFile to failure. Otherwise, the FlowFile will go to success
                // unless it has not yet been processed (its index in the List > updateCounts.length).
                int failureCount = 0;
                int successCount = 0;
                int retryCount = 0;
                for (int i = 0; i < updateCounts.length; i++) {
                    final int updateCount = updateCounts[i];
                    final FlowFile flowFile = batchFlowFiles.get(i);
                    if (updateCount == Statement.EXECUTE_FAILED) {
                        result.routeTo(flowFile, REL_FAILURE);
                        failureCount++;
                    } else {
                        result.routeTo(flowFile, REL_SUCCESS);
                        successCount++;
                    }
                }

                if (failureCount == 0) {
                    // if no failures found, the driver decided not to execute the statements after the
                    // failure, so route the last one to failure.
                    final FlowFile failedFlowFile = batchFlowFiles.get(updateCounts.length);
                    result.routeTo(failedFlowFile, REL_FAILURE);
                    failureCount++;
                }

                if (updateCounts.length < batchFlowFiles.size()) {
                    final List<FlowFile> unexecuted = batchFlowFiles.subList(updateCounts.length + 1, batchFlowFiles.size());
                    for (final FlowFile flowFile : unexecuted) {
                        result.routeTo(flowFile, REL_RETRY);
                        retryCount++;
                    }
                }

                getLogger().error("Failed to update database due to a failed batch update, {}. There were a total of {} FlowFiles that failed, {} that succeeded, "
                        + "and {} that were not execute and will be routed to retry; ", new Object[]{e, failureCount, successCount, retryCount}, e);

                return;

            }

            // Apply default error handling and logging for other Exceptions.
            ExceptionHandler.OnError<RollbackOnFailure, FlowFileGroup> onGroupError
                    = ExceptionHandler.createOnGroupError(context, session, result, REL_FAILURE, REL_RETRY);
            onGroupError = onGroupError.andThen((cl, il, rl, el) -> {
                switch (r.destination()) {
                    case Failure:
                        getLogger().error("Failed to update database for {} due to {}; routing to failure", new Object[] {il.getFlowFiles(), e}, e);
                        break;
                    case Retry:
                        getLogger().error("Failed to update database for {} due to {}; it is possible that retrying the operation will succeed, so routing to retry",
                                new Object[] {il.getFlowFiles(), e}, e);
                        break;
                }
            });
            onGroupError.apply(c, enclosure, r, e);
        });
    }

    @OnScheduled
    public void constructProcess() {
        process = new PutGroup<>();

        process.setLogger(getLogger());
        process.fetchFlowFiles(fetchFlowFiles);
        process.initConnection(initConnection);
        process.groupFetchedFlowFiles(groupFlowFiles);
        process.putFlowFiles(putFlowFiles);
        process.adjustRoute(RollbackOnFailure.createAdjustRoute(REL_FAILURE, REL_RETRY));

        process.onCompleted((c, s, fc, conn) -> {
            try {
                conn.commit();
            } catch (SQLException e) {
                // Throw ProcessException to rollback process session.
                throw new ProcessException("Failed to commit database connection due to " + e, e);
            }
        });

        process.onFailed((c, s, fc, conn, e) -> {
            try {
                conn.rollback();
            } catch (SQLException re) {
                // Just log the fact that rollback failed.
                // ProcessSession will be rollback by the thrown Exception so don't have to do anything here.
                getLogger().warn("Failed to rollback database connection due to %s", new Object[]{re}, re);
            }
        });

        process.cleanup((c, s, fc, conn) -> {
            // make sure that we try to set the auto commit back to whatever it was.
            final boolean autocommit = c.getProperty(AUTO_COMMIT).asBoolean();
            if (fc.originalAutoCommit != autocommit) {
                try {
                    conn.setAutoCommit(fc.originalAutoCommit);
                } catch (final SQLException se) {
                    getLogger().warn("Failed to reset autocommit due to {}", new Object[]{se});
                }
            }
        });

        exceptionHandler = new ExceptionHandler<>();
        exceptionHandler.mapException(e -> {
            if (e instanceof SQLNonTransientException) {
                return ErrorTypes.InvalidInput;
            } else if (e instanceof SQLException) {
                return ErrorTypes.TemporalFailure;
            } else {
                return ErrorTypes.UnknownFailure;
            }
        });
        adjustError = RollbackOnFailure.createAdjustError(getLogger());
        exceptionHandler.adjustError(adjustError);
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSessionFactory sessionFactory) throws ProcessException {
        final Boolean rollbackOnFailure = context.getProperty(RollbackOnFailure.ROLLBACK_ON_FAILURE).asBoolean();
        final Boolean dbTransactionRollbackConfig = context.getProperty(SUPPORT_TRANSACTION_ROLLBACK).asBoolean();
        final Boolean supportTransaction = context.getProperty(SUPPORT_TRANSACTIONS).asBoolean();
        final FunctionContext functionContext = new FunctionContext(rollbackOnFailure,dbTransactionRollbackConfig&supportTransaction);
        functionContext.obtainKeys = context.getProperty(OBTAIN_GENERATED_KEYS).asBoolean();
        RollbackOnFailure.onTrigger(context, sessionFactory, functionContext, getLogger(), session -> process.onTrigger(context, session, functionContext));
    }

    /**
     * Pulls a batch of FlowFiles from the incoming queues. If no FlowFiles are available, returns <code>null</code>.
     * Otherwise, a List of FlowFiles will be returned.
     *
     * If all FlowFiles pulled are not eligible to be processed, the FlowFiles will be penalized and transferred back
     * to the input queue and an empty List will be returned.
     *
     * Otherwise, if the Support Fragmented Transactions property is true, all FlowFiles that belong to the same
     * transaction will be sorted in the order that they should be evaluated.
     *
     * @param context the process context for determining properties
     * @param session the process session for pulling flowfiles
     * @return a FlowFilePoll containing a List of FlowFiles to process, or <code>null</code> if there are no FlowFiles to process
     */
    private FlowFilePoll pollFlowFiles(final ProcessContext context, final ProcessSession session,
                                       final FunctionContext functionContext, final RoutingResult result) {
        // Determine which FlowFile Filter to use in order to obtain FlowFiles.
        final boolean useTransactions = context.getProperty(SUPPORT_TRANSACTIONS).asBoolean();
        boolean fragmentedTransaction = false;

        final int batchSize = context.getProperty(BATCH_SIZE).asInteger();
        final FlowFileFilter dbcpServiceFlowFileFilter = context.getProperty(CONNECTION_POOL).asControllerService(DBCPService.class).getFlowFileFilter(batchSize);
        List<FlowFile> flowFiles;
        if (useTransactions) {
            final TransactionalFlowFileFilter filter = new TransactionalFlowFileFilter(dbcpServiceFlowFileFilter);
            flowFiles = session.get(filter);
            fragmentedTransaction = filter.isFragmentedTransaction();
        } else {
            if (dbcpServiceFlowFileFilter == null) {
                flowFiles = session.get(batchSize);
            } else {
                flowFiles = session.get(dbcpServiceFlowFileFilter);
            }
        }

        if (flowFiles.isEmpty()) {
            return null;
        }

        // If we are supporting fragmented transactions, verify that all FlowFiles are correct
        if (fragmentedTransaction) {
            try {
                if (!isFragmentedTransactionReady(flowFiles, context.getProperty(TRANSACTION_TIMEOUT).asTimePeriod(TimeUnit.MILLISECONDS))) {
                    // Not ready, penalize FlowFiles and put it back to self.
                    flowFiles.forEach(f -> result.routeTo(session.penalize(f), Relationship.SELF));
                    return null;
                }

            } catch (IllegalArgumentException e) {
                // Map relationship based on context, and then let default handler to handle.
                final ErrorTypes.Result adjustedRoute = adjustError.apply(functionContext, ErrorTypes.InvalidInput);
                ExceptionHandler.createOnGroupError(context, session, result, REL_FAILURE, REL_RETRY)
                        .apply(functionContext, () -> flowFiles, adjustedRoute, e);
                return null;
            }

            // sort by fragment index.
            flowFiles.sort(Comparator.comparing(o -> Integer.parseInt(o.getAttribute(FRAGMENT_INDEX_ATTR))));
        }

        return new FlowFilePoll(flowFiles, fragmentedTransaction);
    }


    /**
     * Returns the key that was generated from the given statement, or <code>null</code> if no key
     * was generated or it could not be determined.
     *
     * @param stmt the statement that generated a key
     * @return the key that was generated from the given statement, or <code>null</code> if no key
     *         was generated or it could not be determined.
     */
    private String determineGeneratedKey(final PreparedStatement stmt) {
        try {
            final ResultSet generatedKeys = stmt.getGeneratedKeys();
            if (generatedKeys != null && generatedKeys.next()) {
                return generatedKeys.getString(1);
            }
        } catch (final SQLException sqle) {
            // This is not supported by all vendors. This is a best-effort approach.
        }

        return null;
    }

    /**
     * Determines the SQL statement that should be executed for the given FlowFile
     *
     * @param session the session that can be used to access the given FlowFile
     * @param flowFile the FlowFile whose SQL statement should be executed
     *
     * @return the SQL that is associated with the given FlowFile
     */
    private String getSQL(final ProcessSession session, final FlowFile flowFile) {
        // Read the SQL from the FlowFile's content
        final byte[] buffer = new byte[(int) flowFile.getSize()];
        session.read(flowFile, new InputStreamCallback() {
            @Override
            public void process(final InputStream in) throws IOException {
                StreamUtils.fillBuffer(in, buffer);
            }
        });

        // Create the PreparedStatement to use for this FlowFile.
        final String sql = new String(buffer, StandardCharsets.UTF_8);
        return sql;
    }

    /**
     * Determines which relationship the given FlowFiles should go to, based on a transaction timing out or
     * transaction information not being present. If the FlowFiles should be processed and not transferred
     * to any particular relationship yet, will return <code>null</code>
     *
     * @param flowFiles the FlowFiles whose relationship is to be determined
     * @param transactionTimeoutMillis the maximum amount of time (in milliseconds) that we should wait
     *            for all FlowFiles in a transaction to be present before routing to failure
     * @return the appropriate relationship to route the FlowFiles to, or <code>null</code> if the FlowFiles
     *         should instead be processed
     */
    boolean isFragmentedTransactionReady(final List<FlowFile> flowFiles, final Long transactionTimeoutMillis) throws IllegalArgumentException {
        int selectedNumFragments = 0;
        final BitSet bitSet = new BitSet();

        BiFunction<String, Object[], IllegalArgumentException> illegal = (s, objects) -> new IllegalArgumentException(format(s, objects));

        for (final FlowFile flowFile : flowFiles) {
            final String fragmentCount = flowFile.getAttribute(FRAGMENT_COUNT_ATTR);
            if (fragmentCount == null && flowFiles.size() == 1) {
                return true;
            } else if (fragmentCount == null) {
                throw illegal.apply("Cannot process %s because there are %d FlowFiles with the same fragment.identifier "
                        + "attribute but not all FlowFiles have a fragment.count attribute", new Object[] {flowFile, flowFiles.size()});
            }

            final int numFragments;
            try {
                numFragments = Integer.parseInt(fragmentCount);
            } catch (final NumberFormatException nfe) {
                throw illegal.apply("Cannot process %s because the fragment.count attribute has a value of '%s', which is not an integer",
                        new Object[] {flowFile, fragmentCount});
            }

            if (numFragments < 1) {
                throw illegal.apply("Cannot process %s because the fragment.count attribute has a value of '%s', which is not a positive integer",
                        new Object[] {flowFile, fragmentCount});
            }

            if (selectedNumFragments == 0) {
                selectedNumFragments = numFragments;
            } else if (numFragments != selectedNumFragments) {
                throw illegal.apply("Cannot process %s because the fragment.count attribute has different values for different FlowFiles with the same fragment.identifier",
                        new Object[] {flowFile});
            }

            final String fragmentIndex = flowFile.getAttribute(FRAGMENT_INDEX_ATTR);
            if (fragmentIndex == null) {
                throw illegal.apply("Cannot process %s because the fragment.index attribute is missing", new Object[] {flowFile});
            }

            final int idx;
            try {
                idx = Integer.parseInt(fragmentIndex);
            } catch (final NumberFormatException nfe) {
                throw illegal.apply("Cannot process %s because the fragment.index attribute has a value of '%s', which is not an integer",
                        new Object[] {flowFile, fragmentIndex});
            }

            if (idx < 0) {
                throw illegal.apply("Cannot process %s because the fragment.index attribute has a value of '%s', which is not a positive integer",
                        new Object[] {flowFile, fragmentIndex});
            }

            if (bitSet.get(idx)) {
                throw illegal.apply("Cannot process %s because it has the same value for the fragment.index attribute as another FlowFile with the same fragment.identifier",
                        new Object[] {flowFile});
            }

            bitSet.set(idx);
        }

        if (selectedNumFragments == flowFiles.size()) {
            return true; // no relationship to route FlowFiles to yet - process the FlowFiles.
        }

        long latestQueueTime = 0L;
        for (final FlowFile flowFile : flowFiles) {
            if (flowFile.getLastQueueDate() != null && flowFile.getLastQueueDate() > latestQueueTime) {
                latestQueueTime = flowFile.getLastQueueDate();
            }
        }

        if (transactionTimeoutMillis != null) {
            if (latestQueueTime > 0L && System.currentTimeMillis() - latestQueueTime > transactionTimeoutMillis) {
                throw illegal.apply("The transaction timeout has expired for the following FlowFiles; they will be routed to failure: %s", new Object[] {flowFiles});
            }
        }

        getLogger().debug("Not enough FlowFiles for transaction. Returning all FlowFiles to queue");
        return false;  // not enough FlowFiles for this transaction. Return them all to queue.
    }



    /**
     * A FlowFileFilter that is responsible for ensuring that the FlowFiles returned either belong
     * to the same "fragmented transaction" (i.e., 1 transaction whose information is fragmented
     * across multiple FlowFiles) or that none of the FlowFiles belongs to a fragmented transaction
     */
    static class TransactionalFlowFileFilter implements FlowFileFilter {
        private final FlowFileFilter nonFragmentedTransactionFilter;
        private String selectedId = null;
        private int numSelected = 0;
        private boolean ignoreFragmentIdentifiers = false;

        public TransactionalFlowFileFilter(FlowFileFilter nonFragmentedTransactionFilter) {
            this.nonFragmentedTransactionFilter = nonFragmentedTransactionFilter;
        }

        public boolean isFragmentedTransaction() {
            return !ignoreFragmentIdentifiers;
        }

        private FlowFileFilterResult filterNonFragmentedTransaction(final FlowFile flowFile) {
            if (nonFragmentedTransactionFilter == null) {
                return FlowFileFilterResult.ACCEPT_AND_CONTINUE;
            } else {
                // Use non-fragmented tx filter for further filtering.
                return nonFragmentedTransactionFilter.filter(flowFile);
            }
        }

        @Override
        public FlowFileFilterResult filter(final FlowFile flowFile) {
            final String fragmentId = flowFile.getAttribute(FRAGMENT_ID_ATTR);
            final String fragCount = flowFile.getAttribute(FRAGMENT_COUNT_ATTR);

            // if first FlowFile selected is not part of a fragmented transaction, then
            // we accept any FlowFile that is also not part of a fragmented transaction.
            if (ignoreFragmentIdentifiers) {
                if (fragmentId == null || "1".equals(fragCount)) {
                    return filterNonFragmentedTransaction(flowFile);
                } else {
                    return FlowFileFilterResult.REJECT_AND_CONTINUE;
                }
            }

            if (fragmentId == null || "1".equals(fragCount)) {
                if (selectedId == null) {
                    // Only one FlowFile in the transaction.
                    ignoreFragmentIdentifiers = true;
                    return filterNonFragmentedTransaction(flowFile);
                } else {
                    // we've already selected 1 FlowFile, and this one doesn't match.
                    return FlowFileFilterResult.REJECT_AND_CONTINUE;
                }
            }

            if (selectedId == null) {
                // select this fragment id as the chosen one.
                selectedId = fragmentId;
                numSelected++;
                return FlowFileFilterResult.ACCEPT_AND_CONTINUE;
            }

            if (selectedId.equals(fragmentId)) {
                // fragment id's match. Find out if we have all of the necessary fragments or not.
                final int numFragments;
                if (fragCount != null && JdbcCommon.NUMBER_PATTERN.matcher(fragCount).matches()) {
                    numFragments = Integer.parseInt(fragCount);
                } else {
                    numFragments = Integer.MAX_VALUE;
                }

                if (numSelected >= numFragments - 1) {
                    // We have all of the fragments we need for this transaction.
                    return FlowFileFilterResult.ACCEPT_AND_TERMINATE;
                } else {
                    // We still need more fragments for this transaction, so accept this one and continue.
                    numSelected++;
                    return FlowFileFilterResult.ACCEPT_AND_CONTINUE;
                }
            } else {
                return FlowFileFilterResult.REJECT_AND_CONTINUE;
            }
        }
    }


    /**
     * A simple, immutable data structure to hold a List of FlowFiles and an indicator as to whether
     * or not those FlowFiles represent a "fragmented transaction" - that is, a collection of FlowFiles
     * that all must be executed as a single transaction (we refer to it as a fragment transaction
     * because the information for that transaction, including SQL and the parameters, is fragmented
     * across multiple FlowFiles).
     */
    private static class FlowFilePoll {
        private final List<FlowFile> flowFiles;
        private final boolean fragmentedTransaction;

        public FlowFilePoll(final List<FlowFile> flowFiles, final boolean fragmentedTransaction) {
            this.flowFiles = flowFiles;
            this.fragmentedTransaction = fragmentedTransaction;
        }

        public List<FlowFile> getFlowFiles() {
            return flowFiles;
        }

        public boolean isFragmentedTransaction() {
            return fragmentedTransaction;
        }
    }


    private static class FragmentedEnclosure extends StatementFlowFileEnclosure {

        private final Map<FlowFile, StatementFlowFileEnclosure> flowFileToEnclosure = new HashMap<>();

        public FragmentedEnclosure() {
            super(null);
        }

        public void addFlowFile(final FlowFile flowFile, final StatementFlowFileEnclosure enclosure) {
            addFlowFile(flowFile);
            flowFileToEnclosure.put(flowFile, enclosure);
        }

        public StatementFlowFileEnclosure getTargetEnclosure(final FlowFile flowFile) {
            return flowFileToEnclosure.get(flowFile);
        }
    }

    /**
     * A simple, immutable data structure to hold a Prepared Statement and a List of FlowFiles
     * for which that statement should be evaluated.
     */
    private static class StatementFlowFileEnclosure implements FlowFileGroup {
        private final String sql;
        private PreparedStatement statement;
        private final List<FlowFile> flowFiles = new ArrayList<>();

        public StatementFlowFileEnclosure(String sql) {
            this.sql = sql;
        }

        public PreparedStatement getNewStatement(final Connection conn, final boolean obtainKeys) throws SQLException {
            if (obtainKeys) {
                // Create a new Prepared Statement, requesting that it return the generated keys.
                PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);

                if (stmt == null) {
                    // since we are passing Statement.RETURN_GENERATED_KEYS, calls to conn.prepareStatement will
                    // in some cases (at least for DerbyDB) return null.
                    // We will attempt to recompile the statement without the generated keys being returned.
                    stmt = conn.prepareStatement(sql);
                }

                // If we need to obtain keys, then we cannot do a Batch Update. In this case,
                // we don't need to store the PreparedStatement in the Map because we aren't
                // doing an addBatch/executeBatch. Instead, we will use the statement once
                // and close it.
                return stmt;
            }

            return conn.prepareStatement(sql);
        }

        public PreparedStatement getCachedStatement(final Connection conn) throws SQLException {
            if (statement != null) {
                return statement;
            }

            statement = conn.prepareStatement(sql);
            return statement;
        }

        @Override
        public List<FlowFile> getFlowFiles() {
            return flowFiles;
        }

        public void addFlowFile(final FlowFile flowFile) {
            this.flowFiles.add(flowFile);
        }

        @Override
        public int hashCode() {
            return sql.hashCode();
        }

        @Override
        public boolean equals(final Object obj) {
            if (obj == null) {
                return false;
            }
            if (obj == this) {
                return false;
            }
            if (!(obj instanceof StatementFlowFileEnclosure)) {
                return false;
            }

            final StatementFlowFileEnclosure other = (StatementFlowFileEnclosure) obj;
            return sql.equals(other.sql);
        }
    }
}
