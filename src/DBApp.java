import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Predicate;

public class DBApp {
    private static final String CONFIG = "src/resources/DBApp.config";
    private static final Set<String> OPERATORS = new HashSet<>(Arrays.asList(">", ">=", "<", "<=", "!=", "="));

    private int maxRowsPerPage;
    private int maxEntriesPerNode;

    public DBApp() {
        init();
    }

    public void init() {
        Properties config = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(CONFIG))) {
            config.load(in);
            maxRowsPerPage = Integer.parseInt(config.getProperty("MaximumRowsCountinTablePage").trim());
            maxEntriesPerNode = Integer.parseInt(config.getProperty("MaximumEntriesinOctreeNode").trim());
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot load " + CONFIG + ": " + e, e);
        }
        if (maxRowsPerPage < 1 || maxEntriesPerNode < 1)
            throw new IllegalStateException(CONFIG + ": limits must be positive");
    }

    public void createTable(String strTableName,
                            String strClusteringKeyColumn,
                            Hashtable<String, String> htblColNameType,
                            Hashtable<String, String> htblColNameMin,
                            Hashtable<String, String> htblColNameMax)
            throws DBAppException {
        if (strTableName == null || strClusteringKeyColumn == null || htblColNameType == null
                || htblColNameMin == null || htblColNameMax == null)
            throw new DBAppException("createTable arguments must not be null");
        checkName(strTableName);
        MetadataManager metadata = new MetadataManager();
        if (metadata.hasTable(strTableName))
            throw new DBAppException("Table already exists: " + strTableName);
        if (!htblColNameType.containsKey(strClusteringKeyColumn))
            throw new DBAppException("Clustering key is not a column: " + strClusteringKeyColumn);

        for (Map.Entry<String, String> column : htblColNameType.entrySet()) {
            String name = column.getKey();
            checkName(name);
            String type = Util.canonicalType(column.getValue());
            String min = htblColNameMin.get(name), max = htblColNameMax.get(name);
            if (min == null || max == null)
                throw new DBAppException("Missing min or max for column: " + name);
            if (min.matches("(?s).*[,\\r\\n].*") || max.matches("(?s).*[,\\r\\n].*"))
                throw new DBAppException("min and max cannot contain commas or line breaks: " + name);
            if (Util.compareToBound(Util.parse(type, min), Util.parse(type, max)) > 0)
                throw new DBAppException("min is greater than max for column: " + name);
            metadata.add(new MetaEntry(strTableName, name, type, name.equals(strClusteringKeyColumn), min, max));
        }
        new Table(strTableName, strClusteringKeyColumn).save();
        metadata.save();
    }

    public void createIndex(String strTableName,
                            String[] strarrColName) throws DBAppException {
        if (strarrColName == null || strarrColName.length != 3)
            throw new DBAppException("An octree index needs exactly 3 columns");
        MetadataManager metadata = new MetadataManager();
        Map<String, MetaEntry> columns = metadata.columns(strTableName);
        if (new HashSet<>(Arrays.asList(strarrColName)).size() != 3)
            throw new DBAppException("An octree index needs 3 different columns");

        Object[] low = new Object[3], high = new Object[3];
        for (int d = 0; d < 3; d++) {
            MetaEntry column = columns.get(strarrColName[d]);
            if (column == null)
                throw new DBAppException("No such column: " + strarrColName[d]);
            if (column.isIndexed())
                throw new DBAppException("Column already indexed: " + strarrColName[d]);
            low[d] = Util.parse(column.columnType, column.minValue);
            high[d] = Util.parse(column.columnType, column.maxValue);
        }
        String indexName = String.join("", strarrColName) + "Index";
        if (metadata.indexes(strTableName).containsKey(indexName))
            throw new DBAppException("Index already exists: " + indexName);

        Octree index = new Octree(strTableName, indexName, metadata.clusteringKey(strTableName),
                strarrColName.clone(), low, high, maxEntriesPerNode);
        for (Tuple t : Table.load(strTableName).scan(t -> true))
            index.insert(t);
        index.save();

        for (String name : strarrColName) {
            columns.get(name).indexName = indexName;
            columns.get(name).indexType = "Octree";
        }
        metadata.save();
    }

    public void insertIntoTable(String strTableName,
                                Hashtable<String, Object> htblColNameValue)
            throws DBAppException {
        MetadataManager metadata = new MetadataManager();
        Map<String, MetaEntry> columns = metadata.columns(strTableName);
        checkValues(columns, htblColNameValue, true);
        if (!htblColNameValue.containsKey(metadata.clusteringKey(strTableName)))
            throw new DBAppException("Insert must include the clustering key");

        Tuple tuple = new Tuple(htblColNameValue);
        Table table = Table.load(strTableName);
        table.insert(tuple, maxRowsPerPage);
        table.save();
        for (String name : metadata.indexes(strTableName).keySet()) {
            Octree index = Octree.load(strTableName, name);
            index.insert(tuple);
            index.save();
        }
    }

    public void updateTable(String strTableName,
                            String strClusteringKeyValue,
                            Hashtable<String, Object> htblColNameValue)
            throws DBAppException {
        MetadataManager metadata = new MetadataManager();
        Map<String, MetaEntry> columns = metadata.columns(strTableName);
        String keyColumn = metadata.clusteringKey(strTableName);
        checkValues(columns, htblColNameValue, true);
        if (htblColNameValue.containsKey(keyColumn))
            throw new DBAppException("Update cannot change the clustering key");
        if (strClusteringKeyValue == null)
            throw new DBAppException("Clustering key value must not be null");

        Table table = Table.load(strTableName);
        Tuple old = table.find(Util.parse(columns.get(keyColumn).columnType, strClusteringKeyValue));
        if (old == null)
            return;
        Tuple updated = old.with(htblColNameValue);
        table.replace(updated);

        Map<String, Set<String>> indexes = metadata.indexes(strTableName);
        for (String name : indexes.keySet()) {
            if (Collections.disjoint(indexes.get(name), htblColNameValue.keySet()))
                continue;
            Octree index = Octree.load(strTableName, name);
            index.delete(old);
            index.insert(updated);
            index.save();
        }
    }

    public void deleteFromTable(String strTableName,
                                Hashtable<String, Object> htblColNameValue)
            throws DBAppException {
        MetadataManager metadata = new MetadataManager();
        checkValues(metadata.columns(strTableName), htblColNameValue, false);

        SQLTerm[] terms = new SQLTerm[htblColNameValue.size()];
        int i = 0;
        for (Map.Entry<String, Object> condition : htblColNameValue.entrySet())
            terms[i++] = new SQLTerm(strTableName, condition.getKey(), "=", condition.getValue());
        String[] operators = new String[Math.max(0, terms.length - 1)];
        Arrays.fill(operators, "AND");

        Table table = Table.load(strTableName);
        Vector<Tuple> rows = find(table, metadata, strTableName, terms, operators);
        if (rows.isEmpty())
            return;
        table.delete(rows);
        table.save();
        for (String name : metadata.indexes(strTableName).keySet()) {
            Octree index = Octree.load(strTableName, name);
            for (Tuple t : rows)
                index.delete(t);
            index.save();
        }
    }

    public Iterator selectFromTable(SQLTerm[] arrSQLTerms,
                                    String[] strarrOperators)
            throws DBAppException {
        if (arrSQLTerms == null || arrSQLTerms.length == 0 || arrSQLTerms[0] == null)
            throw new DBAppException("Select needs at least one SQLTerm");
        if (strarrOperators == null || strarrOperators.length != arrSQLTerms.length - 1)
            throw new DBAppException("Select needs exactly one operator between each two SQLTerms");
        for (String operator : strarrOperators)
            if (operator == null || !operator.matches("(?i)AND|OR|XOR"))
                throw new DBAppException("Unsupported operator between terms: " + operator);

        String tableName = arrSQLTerms[0]._strTableName;
        MetadataManager metadata = new MetadataManager();
        Map<String, MetaEntry> columns = metadata.columns(tableName);
        for (SQLTerm term : arrSQLTerms) {
            if (term == null || !tableName.equals(term._strTableName))
                throw new DBAppException("All SQLTerms must use the same table; joins are not supported");
            if (term._strOperator == null || !OPERATORS.contains(term._strOperator))
                throw new DBAppException("Unsupported operator: " + term._strOperator);
            MetaEntry column = columns.get(term._strColumnName);
            if (column == null)
                throw new DBAppException("No such column: " + term._strColumnName);
            Util.checkType(column, term._objValue);
        }
        return find(Table.load(tableName), metadata, tableName, arrSQLTerms, strarrOperators).iterator();
    }

    private Vector<Tuple> find(Table table, MetadataManager metadata, String tableName,
                               SQLTerm[] terms, String[] operators) throws DBAppException {
        Predicate<Tuple> where = t -> matches(t, terms, operators);
        Collection<Object> keys = candidateKeys(metadata, tableName, terms, operators);
        return keys == null ? table.scan(where) : table.fetch(keys, where);
    }

    private Collection<Object> candidateKeys(MetadataManager metadata, String tableName,
                                             SQLTerm[] terms, String[] operators) throws DBAppException {
        if (terms.length == 0)
            return null;
        Set<Object> keys = new TreeSet<>(Util::compare);
        int start = 0;
        for (int i = 0; i < terms.length; i++) {
            if (i < operators.length && operators[i].equalsIgnoreCase("AND"))
                continue;
            Collection<Object> group = groupKeys(metadata, tableName, Arrays.copyOfRange(terms, start, i + 1));
            if (group == null)
                return null;
            keys.addAll(group);
            start = i + 1;
        }
        return keys;
    }

    private Collection<Object> groupKeys(MetadataManager metadata, String tableName, SQLTerm[] group)
            throws DBAppException {
        String keyColumn = metadata.clusteringKey(tableName);
        for (SQLTerm term : group)
            if (term._strColumnName.equals(keyColumn) && term._strOperator.equals("="))
                return Collections.singleton(term._objValue);

        String best = null;
        int bestCount = 0;
        Map<String, Set<String>> indexes = metadata.indexes(tableName);
        for (Map.Entry<String, Set<String>> index : indexes.entrySet()) {
            int count = 0;
            for (SQLTerm term : group)
                if (index.getValue().contains(term._strColumnName) && !term._strOperator.equals("!="))
                    count++;
            if (count > bestCount) {
                best = index.getKey();
                bestCount = count;
            }
        }
        if (best == null)
            return null;

        Octree index = Octree.load(tableName, best);
        List<String> indexColumns = Arrays.asList(index.columns());
        Object[] low = new Object[3], high = new Object[3];
        for (SQLTerm term : group) {
            int d = indexColumns.indexOf(term._strColumnName);
            if (d < 0)
                continue;
            String op = term._strOperator;
            Object value = term._objValue;
            if ((op.startsWith(">") || op.equals("=")) && (low[d] == null || Util.compare(value, low[d]) > 0))
                low[d] = value;
            if ((op.startsWith("<") || op.equals("=")) && (high[d] == null || Util.compare(value, high[d]) < 0))
                high[d] = value;
        }
        return index.search(low, high);
    }

    private static boolean matches(Tuple t, SQLTerm[] terms, String[] operators) {
        if (terms.length == 0)
            return true;
        boolean or = false, xor = false, and = matches(t, terms[0]);
        for (int i = 0; i < operators.length; i++) {
            boolean next = matches(t, terms[i + 1]);
            String operator = operators[i].toUpperCase();
            if (operator.equals("AND")) {
                and &= next;
            } else if (operator.equals("XOR")) {
                xor ^= and;
                and = next;
            } else {
                or |= xor ^ and;
                xor = false;
                and = next;
            }
        }
        return or | (xor ^ and);
    }

    private static boolean matches(Tuple t, SQLTerm term) {
        Object value = t.get(term._strColumnName);
        if (value == null)
            return false;
        int c = Util.compare(value, term._objValue);
        switch (term._strOperator) {
            case ">": return c > 0;
            case ">=": return c >= 0;
            case "<": return c < 0;
            case "<=": return c <= 0;
            case "!=": return c != 0;
            default: return c == 0;
        }
    }

    private static void checkValues(Map<String, MetaEntry> columns, Hashtable<String, Object> values,
                                    boolean checkRange) throws DBAppException {
        if (values == null)
            throw new DBAppException("Column values must not be null");
        for (Map.Entry<String, Object> value : values.entrySet()) {
            MetaEntry column = columns.get(value.getKey());
            if (column == null)
                throw new DBAppException("No such column: " + value.getKey());
            Util.checkType(column, value.getValue());
            if (checkRange && (Util.compareToBound(value.getValue(), Util.parse(column.columnType, column.minValue)) < 0
                    || Util.compareToBound(value.getValue(), Util.parse(column.columnType, column.maxValue)) > 0))
                throw new DBAppException("Value " + value.getValue() + " is outside the range of column "
                        + value.getKey() + " [" + column.minValue + ", " + column.maxValue + "]");
        }
    }

    private static void checkName(String name) throws DBAppException {
        if (name == null || !name.matches("\\w+"))
            throw new DBAppException("Names may only contain letters, digits and underscores: " + name);
    }
}
