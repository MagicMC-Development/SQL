package com.winthier.sql;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.persistence.Column;
import javax.persistence.EntityNotFoundException;
import javax.persistence.Id;
import lombok.Getter;

@Getter
final class SQLColumn {
    private final SQLTable table;
    private final Field field;
    private String columnName;
    private String columnDefinition;
    private final boolean nullable;
    private final int length;
    private final int precision;
    private final SQLType type;
    private final boolean id;
    private Method getterMethod;
    private Method setterMethod;

    SQLColumn(SQLTable table, Field field) {
        this.table = table;
        this.field = field;
        Column columnAnnotation = field.getAnnotation(Column.class);
        if (columnAnnotation != null) {
            this.columnName = columnAnnotation.name();
            this.columnDefinition = columnAnnotation.columnDefinition();
            this.nullable = columnAnnotation.nullable();
            this.length = columnAnnotation.length();
            this.precision = columnAnnotation.precision() > 0 ? columnAnnotation.precision() : 11;
        } else {
            this.nullable = true;
            this.length = 255;
            this.precision = 11;
        }
        this.id = field.getAnnotation(Id.class) != null;
        type = SQLType.of(field);
        if (columnName == null || columnName.isEmpty()) {
            if (type == SQLType.REFERENCE) {
                columnName = SQLUtil.camelToLowerCase(field.getName()) + "_id";
            } else {
                columnName = SQLUtil.camelToLowerCase(field.getName());
            }
        }
        String getterName;
        String fieldCamel = field.getName().substring(0, 1).toUpperCase() + field.getName().substring(1);
        if (field.getType() == boolean.class) {
            getterName = "is" + fieldCamel;
        } else {
            getterName = "get" + fieldCamel;
        }
        String setterName = "set" + fieldCamel;
        try {
            getterMethod = field.getDeclaringClass().getMethod(getterName);
            setterMethod = field.getDeclaringClass().getMethod(setterName, field.getType());
        } catch (NoSuchMethodException nsme) {
            throw new RuntimeException(nsme);
        }
    }

    public String getColumnName() {
        return columnName;
    }

    private String getTypeDefinition() {
        switch (type) {
        case INT:
            return "int(" + precision + ")";
        case STRING:
            return "varchar(" + length + ")";
        case UUID:
            return "varchar(40)";
        case FLOAT:
            return "float";
        case DOUBLE:
            return "double";
        case DATE:
            return "datetime";
        case BOOL:
            return "tinyint(1)";
        case ENUM:
            return "varchar(40)";
        case REFERENCE:
            return "int(11) unsigned";
        default:
            throw new IllegalArgumentException("Type definition not implemented: " + type);
        }
    }

    String getColumnDefinition() {
        if (columnDefinition == null || columnDefinition.isEmpty()) {
            if (id) {
                columnDefinition = "int(" + precision + ") UNSIGNED AUTO_INCREMENT";
            } else {
                columnDefinition = getTypeDefinition() + (!nullable ? " NOT NULL" : " DEFAULT NULL");
            }
        }
        return columnDefinition;
    }

    String getCreateTableFragment() {
        return "`" + getColumnName() + "` " + getColumnDefinition();
    }

    void load(Object inst, ResultSet result) {
        try {
            Object value;
            switch (type) {
            case UUID:
                String str = result.getObject(getColumnName(), String.class);
                if (str == null) {
                    value = null;
                } else {
                    value = UUID.fromString(str);
                }
                break;
            case DATE:
                value = result.getObject(getColumnName(), java.sql.Timestamp.class);
                break;
            case ENUM:
                str = result.getString(getColumnName());
                if (str == null) {
                    value = null;
                } else {
                    try {
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        final Enum<?> theEnum = Enum.valueOf((Class<? extends Enum>)field.getType(), str);
                        value = theEnum;
                    } catch (IllegalArgumentException iae) {
                        iae.printStackTrace();
                        value = null;
                    }
                }
                break;
            case REFERENCE:
                int num = result.getInt(getColumnName());
                if (result.wasNull()) {
                    value = null;
                } else {
                    SQLTable refTable = table.getDatabase().getTable(field.getType());
                    if (refTable == null) {
                        throw new EntityNotFoundException("Table not registered: " + field.getType());
                    }
                    value = refTable.find(num);
                }
                break;
            default:
                value = result.getObject(getColumnName(), field.getType());
            }
            setterMethod.invoke(inst, value);
        } catch (SQLException sqle) {
            throw new RuntimeException(sqle);
        } catch (IllegalAccessException iae) {
            throw new RuntimeException(iae);
        } catch (InvocationTargetException ite) {
            throw new RuntimeException(ite);
        }
    }

    void createSaveFragment(Object inst, List<String> fragments, List<Object> values) {
        Object value = getValue(inst);
        if (value == null) {
            fragments.add("`" + getColumnName() + "`=NULL");
        } else {
            fragments.add("`" + getColumnName() + "`=?");
            if (type == SQLType.REFERENCE) {
                SQLTable refTable = table.getDatabase().getTable(field.getType());
                if (refTable == null) {
                    throw new EntityNotFoundException("Table not registered: " + field.getType());
                }
                if (refTable.getIdColumn() == null) {
                    throw new NullPointerException("Referenced table has no id column: " + value.getClass().getName());
                }
                Object refId = refTable.getIdColumn().getValue(value);
                if (refId == null) throw new NullPointerException("Referenced table has no id: " + value.getClass().getName() + ": " + value);
                values.add(refId);
            } else {
                values.add(value);
            }
        }
    }

    Object getValue(Object inst) {
        try {
            return getterMethod.invoke(inst);
        } catch (IllegalAccessException iae) {
            throw new RuntimeException(iae);
        } catch (InvocationTargetException ite) {
            throw new RuntimeException(ite);
        }
    }

    void setValue(Object inst, Object value) {
        try {
            setterMethod.invoke(inst, value);
        } catch (IllegalAccessException iae) {
            throw new RuntimeException(iae);
        } catch (InvocationTargetException ite) {
            throw new RuntimeException(ite);
        }
    }
}