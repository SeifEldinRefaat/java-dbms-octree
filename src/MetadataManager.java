import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class MetadataManager {
    private static final String HEADER = "TableName,ColumnName,ColumnType,ClusteringKey,IndexName,IndexType,min,max";
    private final List<MetaEntry> entries = new ArrayList<>();

    public MetadataManager() throws DBAppException {
        Path file = file();
        if (!Files.exists(file))
            return;
        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines.subList(Math.min(1, lines.size()), lines.size()))
                if (!line.trim().isEmpty())
                    entries.add(new MetaEntry(line));
        } catch (IOException e) {
            throw new DBAppException("Cannot read metadata.csv: " + e);
        }
    }

    private static Path file() {
        return Util.home().resolve("metadata.csv");
    }

    public boolean hasTable(String table) {
        for (MetaEntry e : entries)
            if (e.tableName.equalsIgnoreCase(table))
                return true;
        return false;
    }

    public Map<String, MetaEntry> columns(String table) throws DBAppException {
        Map<String, MetaEntry> columns = new LinkedHashMap<>();
        for (MetaEntry e : entries)
            if (e.tableName.equals(table))
                columns.put(e.columnName, e);
        if (columns.isEmpty())
            throw new DBAppException("Table does not exist: " + table);
        return columns;
    }

    public String clusteringKey(String table) throws DBAppException {
        for (MetaEntry e : columns(table).values())
            if (e.clusteringKey)
                return e.columnName;
        throw new DBAppException("Table has no clustering key in metadata: " + table);
    }

    public Map<String, Set<String>> indexes(String table) throws DBAppException {
        Map<String, Set<String>> indexes = new LinkedHashMap<>();
        for (MetaEntry e : columns(table).values())
            if (e.isIndexed())
                indexes.computeIfAbsent(e.indexName, k -> new HashSet<>()).add(e.columnName);
        return indexes;
    }

    public void add(MetaEntry entry) {
        entries.add(entry);
    }

    public void save() throws DBAppException {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        for (MetaEntry e : entries)
            lines.add(e.toCsvLine());
        try {
            Files.createDirectories(file().toAbsolutePath().getParent());
            Files.write(file(), lines);
        } catch (IOException e) {
            throw new DBAppException("Cannot write metadata.csv: " + e);
        }
    }
}
