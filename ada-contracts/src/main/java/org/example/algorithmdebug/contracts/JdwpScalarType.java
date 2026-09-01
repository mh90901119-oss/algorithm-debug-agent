package org.example.algorithmdebug.contracts;

/** JDWP 条件可比较的有界标量类型，不允许任意对象语义比较。 */
public enum JdwpScalarType {
    STRING,
    LONG,
    DOUBLE,
    BOOLEAN,
    CHAR,
    ENUM,
    NULL
}
