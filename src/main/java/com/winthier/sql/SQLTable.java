package com.winthier.sql;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import lombok.Getter;

@Getter
public final class SQLTable<E> {
    private final Class<E> clazz;
    private final SQLDatabase database;
    private String tableName;
    private List<SQLColumn> columns;
    private SQLColumn idColumn;

    SQLTable(Class<E> clazz, SQLDatabase database) {
        this.clazz = clazz;
        this.database = database;
        Table tableAnnotation = clazz.getAnnotation(Table.class);
        if (tableAnnotation != null) {
            tableName = tableAnnotation.name();
        }
    }

    String getTableName() {
        if (tableName == null || tableName.isEmpty()) {
            tableName = SQLUtil.camelToLowerCase(clazz.getSimpleName());
        }
        return tableName;
    }

    List<SQLColumn> getColumns() {
        if (columns == null) {
            columns = new ArrayList<>();
            for (Field field: clazz.getDeclaredFields()) {
                if (Modifier.isTransient(field.getModifiers())
                    || field.getAnnotation(OneToMany.class) != null
                    || Collection.class.isAssignableFrom(field.getType())
                    || Map.class.isAssignableFrom(field.getType())) continue;
                SQLColumn column = new SQLColumn(this, field);
                columns.add(column);
                if (column.isId()) idColumn = column;
            }
        }
        return columns;
    }

    SQLColumn getColumn(String label) {
        for (SQLColumn column: getColumns()) {
            if (column.getColumnName().equals(label)) return column;
            if (column.getField().getName().equals(label)) return column;
        }
        return null;
    }

    public String getCreateTableStatement() {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS `").append(getTableName()).append("` (\n  ");
        sb.append(getColumns().get(0).getCreateTableFragment());
        for (int i = 1; i < getColumns().size(); ++i) {
            sb.append(",\n  ");
            sb.append(getColumns().get(i).getCreateTableFragment());
        }
        if (idColumn != null) sb.append(",\n  PRIMARY KEY (`").append(idColumn.getColumnName()).append("`)");
        sb.append("\n)");
        return sb.toString();
    }

    public int getRowCount() {
        try (Statement statement = database.getConnection().createStatement()) {
            ResultSet result = statement.executeQuery("SELECT COUNT(*) `count` FROM `" + getTableName() + "`");
            result.next();
            return result.getInt("count");
        } catch (SQLException sqle) {
            throw new RuntimeException(sqle);
        }
    }

    public E createInstance(ResultSet result) {
        try {
            E row = clazz.newInstance();
            for (SQLColumn column: getColumns()) {
                column.load(row, result);
            }
            if (row instanceof SQLInterface) {
                ((SQLInterface)row).onLoad(result);
            }
            return row;
        } catch (InstantiationException ie) {
            throw new RuntimeException(ie);
        } catch (IllegalAccessException iae) {
            throw new RuntimeException(iae);
        }
    }

    int save(E inst) {
        StringBuilder sb = new StringBuilder();
        String postFix;
        Integer idValue = idColumn == null ? null : (Integer)idColumn.getValue(inst);
        if (idValue == null) {
            sb.append("INSERT INTO `" + getTableName() + "` SET ");
            postFix = "";
        } else {
            sb.append("UPDATE `" + getTableName() + "` SET ");
            postFix = " WHERE `" + idColumn.getColumnName() + "` = " + idValue;
        }
        List<String> fragments = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (SQLColumn column: getColumns()) {
            if (column == idColumn) continue;
            column.createSaveFragment(inst, fragments, values);
        }
        sb.append(fragments.get(0));
        for (int i = 1; i < fragments.size(); ++i) sb.append(", ").append(fragments.get(i));
        sb.append(postFix);
        try (PreparedStatement statement = database.getConnection().prepareStatement(sb.toString(), Statement.RETURN_GENERATED_KEYS)) {
            SQLUtil.formatStatement(statement, values);
            statement.executeUpdate();
            ResultSet result = statement.getGeneratedKeys();
            if (result.next()) {
                int newId = result.getInt(1);
                if (idColumn != null) {
                    idColumn.setValue(inst, newId);
                }
                return newId;
            } else {
                return -1;
            }
        } catch (SQLException sqle) {
            throw new RuntimeException(sqle);
        }
    }

    public E find(int id) {
        if (idColumn == null) throw new IllegalStateException("No id column defined: " + clazz.getName());
        try (Statement statement = database.getConnection().createStatement()) {
            ResultSet result = statement.executeQuery("SELECT * FROM " + getTableName() + " WHERE " + idColumn.getColumnName() + " = " + id);
            E row;
            if (result.next()) {
                row = createInstance(result);
            } else {
                row = null;
            }
            result.close();
            return row;
        } catch (SQLException sqle) {
            throw new RuntimeException(sqle);
        }
    }

    public Finder find() {
        return new Finder();
    }

    public enum Comparison {
        EQ("="),
        NEQ("!="),
        LT("<"),
        GT(">"),
        LTE("<="),
        GTE(">=");
        public final String symbol;
        Comparison(String symbol) {
            this.symbol = symbol;
        }
    }

    public final class Finder {
        private final StringBuilder sb = new StringBuilder();
        private final List<Object> values = new ArrayList<>();
        private String conj = " WHERE ";
        private int limit = -1;
        private final List<String> order = new ArrayList<>();

        Finder() {
            sb.append("SELECT * FROM `" + getTableName() + "`");
        }

        public Finder compare(String label, Comparison comp, Object value) {
            if (value == null) throw new IllegalArgumentException("Value cannot be null!");
            SQLColumn column = getColumn(label);
            if (column == null) throw new IllegalArgumentException("Column not found in " + clazz.getName() + ": " + label);
            String columnName = column.getColumnName();
            sb.append(conj).append("`").append(columnName).append("`").append(" " + comp.symbol + " ?");
            values.add(value);
            conj = " AND ";
            return this;
        }

        public Finder eq(String label, Object value) {
            return compare(label, Comparison.EQ, value);
        }

        public Finder neq(String label, Object value) {
            return compare(label, Comparison.NEQ, value);
        }

        public Finder gt(String label, Object value) {
            return compare(label, Comparison.GT, value);
        }

        public Finder gte(String label, Object value) {
            return compare(label, Comparison.GTE, value);
        }

        public Finder lt(String label, Object value) {
            return compare(label, Comparison.LT, value);
        }

        public Finder lte(String label, Object value) {
            return compare(label, Comparison.LTE, value);
        }

        public Finder or() {
            conj = " OR ";
            return this;
        }

        private Finder orderBy(String label, String direction) {
            SQLColumn column = getColumn(label);
            if (column == null) throw new IllegalArgumentException("Column not found in " + clazz.getName() + ": " + label);
            order.add("`" + column.getColumnName() + "` " + direction);
            return this;
        }

        public Finder limit(int newLimit) {
            limit = newLimit;
            return this;
        }

        public Finder orderByAscending(String label) {
            orderBy(label, "ASC");
            return this;
        }

        public Finder orderByDescending(String label) {
            orderBy(label, "DESC");
            return this;
        }

        public E findUnique() {
            limit(1);
            try (ResultSet result = getSelectStatement().executeQuery()) {
                if (result.next()) {
                    return createInstance(result);
                } else {
                    return null;
                }
            } catch (SQLException sqle) {
                throw new RuntimeException(sqle);
            }
        }

        public List<E> findList() {
            List<E> list = new ArrayList<>();
            try (ResultSet result = getSelectStatement().executeQuery()) {
                while (result.next()) {
                    list.add(createInstance(result));
                }
            } catch (SQLException sqle) {
                throw new RuntimeException(sqle);
            }
            return list;
        }

        PreparedStatement getSelectStatement() throws SQLException {
            if (!order.isEmpty()) {
                sb.append(" ORDER BY ").append(order.get(0));
                for (int i = 1; i < order.size(); ++i) {
                    sb.append(", ").append(order.get(i));
                }
            }
            if (limit > 0) {
                sb.append(" LIMIT " + limit);
            }
            PreparedStatement statement = database.getConnection().prepareStatement(sb.toString());
            SQLUtil.formatStatement(statement, values);
            return statement;
        }
    }
}