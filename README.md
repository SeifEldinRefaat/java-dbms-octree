# DBMS — a relational database engine written from scratch in Java

A working storage engine with a SQL-like API, page-based persistence, and a
three-dimensional **octree** index with query planning. No database library, no
ORM, no embedded engine — the pages, the index, the metadata catalogue and the
query path are all implemented directly.

## Why this exists

Most people learn databases by using one. This was built to learn them by
implementing one: what a page actually is, why an index helps only sometimes,
and what a query planner is really choosing between.

The interesting part is not the CRUD surface — it is the two decisions the
engine makes on every read, in `DBApp.find`:

1. **Can an index serve this?** `candidateKeys` inspects the `SQLTerm`s and the
   catalogue to decide whether an octree covers the predicate. If one does, it
   returns candidate clustering keys; if not, it returns `null` and the engine
   falls back to a full scan.
2. **Which pages can be skipped?** Each page records the min and max clustering
   key it holds, so `Table.fetch` and `Table.scan` can prune pages that cannot
   contain a match without deserializing them.

## Architecture

```
DBApp              public API — createTable, createIndex, insert, update, delete, select
  ├── MetadataManager   catalogue in metadata.csv: columns, types, bounds, clustering key
  ├── Table             page-based heap storage; each page tracks its key range
  │     └── page_N.class    serialized Vector<Tuple>, capped at MaximumRowsCountinTablePage
  ├── Octree            3-D spatial index over exactly three columns
  │     └── index_NAME.class    serialized tree, nodes split at MaximumEntriesinOctreeNode
  ├── SQLTerm           one predicate: table, column, operator, value
  ├── Tuple             one row
  └── Util              type checking, serialization, path resolution
```

**Storage.** Tables are heaps of fixed-capacity pages, each serialized to its
own file. Rows are ordered by the clustering key, so a page holds a contiguous
key range and records its own min and max — which is what makes page pruning
possible.

**Indexing.** The index is an octree, not a B-tree. It partitions a
three-dimensional space defined by the per-column `min`/`max` bounds supplied at
`createTable`, subdividing a node into eight children once it exceeds
`MaximumEntriesinOctreeNode`, to a depth ceiling of 32. This makes it a genuine
multi-dimensional index: a predicate over all three indexed columns is answered
by descending one tree, rather than intersecting three separate single-column
indexes.

That design has a real consequence worth stating: an octree index must cover
**exactly three distinct columns**, which is why `createIndex` rejects anything
else. It is excellent for conjunctive predicates across its three dimensions and
no help at all for a predicate on a column it does not cover.

## API

```java
DBApp db = new DBApp();
db.init();

// Column types and the value bounds the octree partitions over
Hashtable<String, String> types = new Hashtable<>();
types.put("id", "java.lang.Integer");
types.put("name", "java.lang.String");
types.put("gpa", "java.lang.Double");

Hashtable<String, String> mins = new Hashtable<>();
mins.put("id", "0");  mins.put("name", "A");  mins.put("gpa", "0.0");

Hashtable<String, String> maxes = new Hashtable<>();
maxes.put("id", "10000");  maxes.put("name", "zzz");  maxes.put("gpa", "4.0");

db.createTable("Student", "id", types, mins, maxes);

// An octree index needs exactly three distinct columns
db.createIndex("Student", new String[]{"id", "name", "gpa"});

Hashtable<String, Object> row = new Hashtable<>();
row.put("id", 1); row.put("name", "Ada"); row.put("gpa", 3.9);
db.insertIntoTable("Student", row);

// Predicates combine with AND / OR / XOR
SQLTerm[] terms = {
    new SQLTerm("Student", "gpa", ">=", 3.5),
    new SQLTerm("Student", "name", "!=", "Bob")
};
Iterator results = db.selectFromTable(terms, new String[]{"AND"});
```

| Method | Notes |
|---|---|
| `createTable(name, clusteringKey, types, mins, maxes)` | Bounds define the index space |
| `createIndex(name, String[3])` | Exactly three distinct columns |
| `insertIntoTable(name, values)` | Type-checked against the catalogue |
| `updateTable(name, clusteringKeyValue, values)` | Located by clustering key |
| `deleteFromTable(name, values)` | Matches on the supplied columns |
| `selectFromTable(SQLTerm[], String[])` | `n` terms require `n-1` operators |

Term operators: `>` `>=` `<` `<=` `!=` `=`. Between terms: `AND` `OR` `XOR`.

## Configuration

`src/resources/DBApp.config`:

```
MaximumRowsCountinTablePage = 200
MaximumEntriesinOctreeNode  = 16
```

Page size trades scan cost against deserialization cost; node capacity trades
tree depth against the linear scan inside a leaf.

## Build and run

Requires a JDK and Maven. Sources are in `src/` (declared via `sourceDirectory`
in `pom.xml` rather than the Maven default layout).

```bash
mvn compile
java -cp target/classes DBAppCheck    # exercises the engine end to end
```