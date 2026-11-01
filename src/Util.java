import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

final class Util {
    private static final List<String> TYPES = Arrays.asList(
            "java.lang.Integer", "java.lang.String", "java.lang.Double", "java.util.Date");

    private Util() {
    }

    static String canonicalType(String type) throws DBAppException {
        for (String t : TYPES)
            if (t.equalsIgnoreCase(type))
                return t;
        throw new DBAppException("Unsupported column type: " + type);
    }

    static Object parse(String type, String text) throws DBAppException {
        try {
            switch (type) {
                case "java.lang.Integer":
                    return Integer.valueOf(text.trim());
                case "java.lang.Double":
                    return Double.valueOf(text.trim());
                case "java.util.Date":
                    return Date.from(LocalDate.parse(text.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant());
                case "java.lang.String":
                    return text;
                default:
                    throw new DBAppException("Unsupported column type: " + type);
            }
        } catch (RuntimeException e) {
            throw new DBAppException("Cannot read '" + text + "' as " + type);
        }
    }

    static void checkType(MetaEntry column, Object value) throws DBAppException {
        try {
            if (!Class.forName(column.columnType).isInstance(value))
                throw new DBAppException("Column " + column.columnName + " expects " + column.columnType
                        + " but got " + (value == null ? "null" : value.getClass().getName()));
        } catch (ClassNotFoundException e) {
            throw new DBAppException("Unknown type in metadata: " + column.columnType);
        }
    }

    @SuppressWarnings("unchecked")
    static int compare(Object a, Object b) {
        return ((Comparable<Object>) a).compareTo(b);
    }

    static int compareToBound(Object value, Object bound) {
        return value instanceof String ? ((String) value).compareToIgnoreCase((String) bound) : compare(value, bound);
    }

    static Path home() {
        return Paths.get(System.getProperty("dbapp.home", "."));
    }

    static Path tableFile(String table, String file) {
        return home().resolve("data").resolve(table).resolve(file);
    }

    static Object read(Path path) throws DBAppException {
        try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            return in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new DBAppException("Cannot read " + path + ": " + e);
        }
    }

    static void write(Path path, Serializable object) throws DBAppException {
        try {
            Files.createDirectories(path.getParent());
            try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
                out.writeObject(object);
            }
        } catch (IOException e) {
            throw new DBAppException("Cannot write " + path + ": " + e);
        }
    }

    static void delete(Path path) throws DBAppException {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new DBAppException("Cannot delete " + path + ": " + e);
        }
    }
}
