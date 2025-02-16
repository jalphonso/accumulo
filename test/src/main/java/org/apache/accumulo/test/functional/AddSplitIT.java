/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.test.functional;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.admin.TabletMergeability;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.metadata.schema.TabletMergeabilityMetadata;
import org.apache.accumulo.core.metadata.schema.TabletsMetadata;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.harness.AccumuloClusterHarness;
import org.apache.hadoop.io.Text;
import org.junit.jupiter.api.Test;

public class AddSplitIT extends AccumuloClusterHarness {

  @Override
  protected Duration defaultTimeout() {
    return Duration.ofMinutes(1);
  }

  @Test
  public void addSplitTest() throws Exception {

    String tableName = getUniqueNames(1)[0];
    try (AccumuloClient c = Accumulo.newClient().from(getClientProps()).build()) {
      c.tableOperations().create(tableName);

      insertData(c, tableName, 1L);

      TreeSet<Text> splits = new TreeSet<>();
      splits.add(new Text(String.format("%09d", 333)));
      splits.add(new Text(String.format("%09d", 666)));

      c.tableOperations().addSplits(tableName, splits);

      Thread.sleep(100);

      Collection<Text> actualSplits = c.tableOperations().listSplits(tableName);

      if (!splits.equals(new TreeSet<>(actualSplits))) {
        throw new Exception(splits + " != " + actualSplits);
      }

      verifyData(c, tableName, 1L);
      insertData(c, tableName, 2L);

      // did not clear splits on purpose, it should ignore existing split points
      // and still create the three additional split points

      splits.add(new Text(String.format("%09d", 200)));
      splits.add(new Text(String.format("%09d", 500)));
      splits.add(new Text(String.format("%09d", 800)));

      c.tableOperations().addSplits(tableName, splits);

      Thread.sleep(100);

      actualSplits = c.tableOperations().listSplits(tableName);

      if (!splits.equals(new TreeSet<>(actualSplits))) {
        throw new Exception(splits + " != " + actualSplits);
      }

      verifyData(c, tableName, 2L);

      TableId id = TableId.of(c.tableOperations().tableIdMap().get(tableName));
      try (TabletsMetadata tm = getServerContext().getAmple().readTablets().forTable(id).build()) {
        // Default for user created tablets should be mergeability set to NEVER
        tm.stream().forEach(tablet -> assertEquals(TabletMergeabilityMetadata.never(),
            tablet.getTabletMergeability()));
      }
    }
  }

  @Test
  public void addSplitWithMergeabilityTest() throws Exception {
    String tableName = getUniqueNames(1)[0];
    try (AccumuloClient c = Accumulo.newClient().from(getClientProps()).build()) {
      c.tableOperations().create(tableName);

      SortedMap<Text,TabletMergeability> splits = new TreeMap<>();
      splits.put(new Text(String.format("%09d", 333)), TabletMergeability.always());
      splits.put(new Text(String.format("%09d", 666)), TabletMergeability.never());
      splits.put(new Text(String.format("%09d", 888)),
          TabletMergeability.after(Duration.ofSeconds(100)));
      splits.put(new Text(String.format("%09d", 999)),
          TabletMergeability.after(Duration.ofDays(1)));

      c.tableOperations().putSplits(tableName, splits);
      Thread.sleep(100);
      assertEquals(splits.keySet(), new TreeSet<>(c.tableOperations().listSplits(tableName)));

      TableId id = TableId.of(c.tableOperations().tableIdMap().get(tableName));
      try (TabletsMetadata tm = getServerContext().getAmple().readTablets().forTable(id).build()) {
        tm.stream().forEach(t -> {
          // default tablet should be set to never
          if (t.getEndRow() == null) {
            assertEquals(TabletMergeability.never(),
                t.getTabletMergeability().getTabletMergeability());
          } else {
            // New splits should match the original setting in the map
            assertEquals(splits.get(t.getEndRow()),
                t.getTabletMergeability().getTabletMergeability());
          }
        });
      }
    }
  }

  private void verifyData(AccumuloClient client, String tableName, long ts) throws Exception {
    try (Scanner scanner = client.createScanner(tableName, Authorizations.EMPTY)) {

      Iterator<Entry<Key,Value>> iter = scanner.iterator();

      for (int i = 0; i < 10000; i++) {
        if (!iter.hasNext()) {
          throw new Exception("row " + i + " not found");
        }

        Entry<Key,Value> entry = iter.next();

        String row = String.format("%09d", i);

        if (!entry.getKey().getRow().equals(new Text(row))) {
          throw new Exception("unexpected row " + entry.getKey() + " " + i);
        }

        if (entry.getKey().getTimestamp() != ts) {
          throw new Exception("unexpected ts " + entry.getKey() + " " + ts);
        }

        if (Integer.parseInt(entry.getValue().toString()) != i) {
          throw new Exception("unexpected value " + entry + " " + i);
        }
      }

      if (iter.hasNext()) {
        throw new Exception("found more than expected " + iter.next());
      }
    }
  }

  private void insertData(AccumuloClient client, String tableName, long ts) throws Exception {
    try (BatchWriter bw = client.createBatchWriter(tableName)) {
      for (int i = 0; i < 10000; i++) {
        String row = String.format("%09d", i);
        Mutation m = new Mutation(new Text(row));
        m.put(new Text("cf1"), new Text("cq1"), ts, new Value(Integer.toString(i)));
        bw.addMutation(m);
      }
    }
  }
}
