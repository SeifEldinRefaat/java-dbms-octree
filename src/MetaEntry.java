public class MetaEntry {
    public String tableName;
    public String columnName;
    public String columnType;
    public boolean clusteringKey;
    public String indexName = "null";
    public String indexType = "null";
    public String minValue;
    public String maxValue;

    public MetaEntry(String tableName, String columnName, String columnType, boolean clusteringKey,
                     String minValue, String maxValue) {
        this.tableName = tableName;
        this.columnName = columnName;
        this.columnType = columnType;
        this.clusteringKey = clusteringKey;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    public MetaEntry(String csvLine) throws DBAppException {
        String[] parts = csvLine.split(",", -1);
        if (parts.length != 8)
            throw new DBAppException("Malformed metadata line: " + csvLine);
        tableName = parts[0];
        columnName = parts[1];
        columnType = parts[2];
        clusteringKey = Boolean.parseBoolean(parts[3]);
        indexName = parts[4];
        indexType = parts[5];
        minValue = parts[6];
        maxValue = parts[7];
    }

    public boolean isIndexed() {
        return !indexName.equals("null");
    }

    public String toCsvLine() {
        return String.join(",", tableName, columnName, columnType, clusteringKey ? "True" : "False",
                indexName, indexType, minValue, maxValue);
    }
}
