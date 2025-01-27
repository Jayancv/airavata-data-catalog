package org.apache.airavata.datacatalog.api.query.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.airavata.datacatalog.api.model.MetadataSchemaEntity;
import org.apache.airavata.datacatalog.api.model.MetadataSchemaFieldEntity;
import org.apache.airavata.datacatalog.api.query.MetadataSchemaQueryWriter;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.util.SqlShuttle;
import org.springframework.stereotype.Component;

@Component
public class PostgresqlMetadataSchemaQueryWriterImpl implements MetadataSchemaQueryWriter {

    private static final class MetadataSchemaFieldFilterRewriter extends SqlShuttle {

        final Collection<MetadataSchemaEntity> metadataSchemas;
        final Map<String, String> tableAliases;
        final StringBuilder sql = new StringBuilder();
        // Maintain queue of binary logical operators so we know when to
        // open/close parentheses and when to add "AND" and "OR" to the query
        Deque<SqlCall> binaryLogicalOperatorNodes = new ArrayDeque<>();

        MetadataSchemaFieldFilterRewriter(Collection<MetadataSchemaEntity> metadataSchemas,
                Map<String, String> tableAliases) {
            this.metadataSchemas = metadataSchemas;
            this.tableAliases = tableAliases;
        }

        MetadataSchemaFieldEntity resolveMetadataSchemaField(SqlIdentifier sqlIdentifier) {

            MetadataSchemaEntity metadataSchema = null;
            String fieldName = null;
            if (sqlIdentifier.names.size() == 2) {
                String tableName = sqlIdentifier.names.get(0);
                metadataSchema = resolveMetadataSchema(tableName);
                fieldName = sqlIdentifier.names.get(1);
            } else if (sqlIdentifier.names.size() == 1) {
                // TODO: just pick the first one, but in general we would need
                // to look through all of the metadata schemas to find the one
                // that this field belongs to
                metadataSchema = this.metadataSchemas.iterator().next();
                fieldName = sqlIdentifier.names.get(0);
            } else {
                throw new RuntimeException("Unexpected sqlIdentifier: " + sqlIdentifier);
            }

            for (MetadataSchemaFieldEntity metadataSchemaField : metadataSchema.getMetadataSchemaFields()) {
                if (metadataSchemaField.getFieldName().equals(fieldName)) {
                    return metadataSchemaField;
                }
            }
            // If none matched, must not be a metadata schema field
            return null;
        }

        MetadataSchemaEntity resolveMetadataSchema(String tableOrAliasName) {
            String tableName = tableOrAliasName;
            if (this.tableAliases.containsKey(tableOrAliasName)) {
                tableName = this.tableAliases.get(tableOrAliasName);
            }
            return findMetadataSchema(tableName);
        }

        MetadataSchemaEntity findMetadataSchema(String schemaName) {
            for (MetadataSchemaEntity metadataSchema : this.metadataSchemas) {
                if (metadataSchema.getSchemaName().equals(schemaName)) {
                    return metadataSchema;
                }
            }
            return null;
        }

        public String finalizeSql() {
            while (!this.binaryLogicalOperatorNodes.isEmpty()) {
                this.binaryLogicalOperatorNodes.pop();
                this.sql.append(" ) ");
            }
            return this.sql.toString();
        }

        @Override
        public SqlNode visit(SqlCall call) {
            SqlCall currentOperator = this.binaryLogicalOperatorNodes.peek();
            while (currentOperator != null
                    && !call.getParserPosition().overlaps(currentOperator.getParserPosition())) {
                this.binaryLogicalOperatorNodes.remove();
                currentOperator = this.binaryLogicalOperatorNodes.peek();
                this.sql.append(" ) ");
                this.sql.append(currentOperator.getOperator().toString());
                this.sql.append(" ");
            }
            if (call.getKind() == SqlKind.NOT) {
                this.sql.append(" NOT ");
            } else if (call.getKind() == SqlKind.AND || call.getKind() == SqlKind.OR) {
                this.binaryLogicalOperatorNodes.push(call);
                this.sql.append("( ");
            } else {
                SqlNode sqlNode = call.getOperandList().get(0);
                // TODO: this assumes that there would only ever be one metadata schema field
                // and that it comes first and the second operand is a literal
                if (sqlNode.isA(Set.of(SqlKind.IDENTIFIER))) {
                    SqlIdentifier sqlIdentifier = (SqlIdentifier) sqlNode;
                    MetadataSchemaFieldEntity metadataSchemaField = resolveMetadataSchemaField(sqlIdentifier);
                    if (metadataSchemaField != null) {
                        // TODO: assuming an alias
                        sql.append(sqlIdentifier.names.get(0));
                        sql.append(".");
                        sql.append("metadata @@ '");
                        sql.append(metadataSchemaField.getJsonPath());
                        sql.append(" ");
                        switch (call.getOperator().kind) {
                            case EQUALS:
                                sql.append(" == ");
                                break;
                            default:
                                sql.append(call.getOperator().kind.sql);
                                break;
                        }
                        sql.append(call.getOperandList().get(1).toSqlString(new PostgresqlSqlDialect(
                                PostgresqlSqlDialect.DEFAULT_CONTEXT.withLiteralQuoteString("\""))));
                        sql.append("'");
                    } else {
                        sql.append(call.toSqlString(PostgresqlSqlDialect.DEFAULT));
                    }
                }

                if (currentOperator != null && !(call.getParserPosition().getEndColumnNum() == currentOperator
                        .getParserPosition().getEndColumnNum()
                        && call.getParserPosition().getEndLineNum() == currentOperator.getParserPosition()
                                .getEndLineNum())) {
                    sql.append(" ");
                    sql.append(currentOperator.getOperator().toString());
                    sql.append(" ");
                }
            }
            return super.visit(call);
        }
    }

    @Override
    public String rewriteQuery(SqlNode sqlNode, Collection<MetadataSchemaEntity> metadataSchemas,
            Map<String, String> tableAliases) {
        StringBuilder sb = new StringBuilder();

        sb.append(writeCommonTableExpressions(metadataSchemas));
        sb.append(" SELECT * FROM ");
        sb.append(((SqlSelect) sqlNode).getFrom().toSqlString(PostgresqlSqlDialect.DEFAULT));
        sb.append(" WHERE ");
        sb.append(rewriteWhereClauseFilters(sqlNode, metadataSchemas, tableAliases));
        return sb.toString();
    }

    private String rewriteWhereClauseFilters(SqlNode sqlNode, Collection<MetadataSchemaEntity> metadataSchemas,
            Map<String, String> tableAliases) {
        MetadataSchemaFieldFilterRewriter filterRewriter = new MetadataSchemaFieldFilterRewriter(metadataSchemas,
                tableAliases);
        sqlNode.accept(filterRewriter);
        return filterRewriter.finalizeSql();
    }

    String writeCommonTableExpressions(Collection<MetadataSchemaEntity> metadataSchemas) {
        StringBuilder sb = new StringBuilder();
        List<String> commonTableExpressions = new ArrayList<>();
        for (MetadataSchemaEntity metadataSchema : metadataSchemas) {
            commonTableExpressions.add(writeCommonTableExpression(metadataSchema));
        }
        sb.append("WITH ");
        sb.append(String.join(", ", commonTableExpressions));
        return sb.toString();
    }

    String writeCommonTableExpression(MetadataSchemaEntity metadataSchemaEntity) {

        StringBuilder sb = new StringBuilder();
        sb.append(metadataSchemaEntity.getSchemaName());
        sb.append(" AS (");
        sb.append("select dp_.data_product_id, dp_.parent_data_product_id, dp_.external_id, dp_.name, dp_.metadata ");
        // for (MetadataSchemaFieldEntity field :
        // metadataSchemaEntity.getMetadataSchemaFields()) {
        // TODO: include each field as well?
        // }
        sb.append("from data_product dp_ ");
        sb.append("inner join data_product_metadata_schema dpms_ on dpms_.data_product_id = dp_.data_product_id ");
        sb.append("inner join metadata_schema ms_ on ms_.metadata_schema_id = dpms_.metadata_schema_id ");
        sb.append("where ms_.metadata_schema_id = " + metadataSchemaEntity.getMetadataSchemaId());
        sb.append(")");
        return sb.toString();
    }
}
