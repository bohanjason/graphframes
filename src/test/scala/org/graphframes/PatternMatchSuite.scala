/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.graphframes

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.Column
import org.apache.spark.sql.functions.{col, lit, when}


class PatternMatchSuite extends SparkFunSuite with GraphFrameTestSparkContext {

  @transient var v: DataFrame = _
  @transient var e: DataFrame = _
  @transient var g: GraphFrame = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    v = sqlContext.createDataFrame(List(
      (0L, "a", "f"),
      (1L, "b", "m"),
      (2L, "c", "m"),
      (3L, "d", "f"))).toDF("id", "attr", "gender")
    e = sqlContext.createDataFrame(List(
      (0L, 1L, "friend"),
      (1L, 2L, "friend"),
      (2L, 3L, "follow"),
      (2L, 0L, "unknown"))).toDF("src", "dst", "relationship")
    g = GraphFrame(v, e)
  }

  override def afterAll(): Unit = {
    v = null
    e = null
    g = null
    super.afterAll()
  }

  test("empty query should return nothing") {
    val emptiness = g.find("")
    assert(emptiness.count() === 0)
  }

  test("filter edges and drop isolated vertices") {
    // string expression
    val s = "relationship = 'friend'"
    // column expression
    val c = col("relationship") === "friend"
    // expected subgraph vertices 
    val expected_v =  Set(
      Row(0L, "a", "f"),
      Row(1L, "b", "m"),
      Row(2L, "c", "m")
    )
    // expected subgraph edges
    val expected_e =  Set(
      Row(0L, 1L, "friend"),
      Row(1L, 2L, "friend")
    )

    val res_s = g.filterEdges(s)
    assert(res_s.vertices.collect().toSet === v.collect().toSet)
    assert(res_s.edges.collect().toSet === expected_e)

    val res_c = g.filterEdges(c)
    assert(res_c.vertices.collect().toSet === v.collect().toSet)
    assert(res_c.edges.collect().toSet === expected_e)

    val res = res_s.dropIsolatedVertices()
    assert(res.vertices.collect().toSet === expected_v)
    assert(res.edges.collect().toSet === expected_e)
  }

  test("filter vertices") {
    // string expression
    val s = "id > 0"
    // column expression
    val c = col("id") > 0
    // expected subgraph vertices 
    val expected_v =  Set(
      Row(1L, "b", "m"),
      Row(2L, "c", "m"),
      Row(3L, "d", "f")
    )
    // expected subgraph edges
    val expected_e =  Set(
      Row(1L, 2L, "friend"),
      Row(2L, 3L, "follow")
    )

    val res_s = g.filterVertices(s)
    assert(res_s.vertices.collect().toSet === expected_v)
    assert(res_s.edges.collect().toSet === expected_e)

    val res_c = g.filterVertices(c)
    assert(res_c.vertices.collect().toSet === expected_v)
    assert(res_c.edges.collect().toSet === expected_e)
  }

  test("triangles") {
    val triangles = g.find("(a)-[]->(b); (b)-[]->(c); (c)-[]->(a)")
      .select("a.id", "b.id", "c.id")

    assert(triangles.collect().toSet === Set(
      Row(0L, 1L, 2L),
      Row(2L, 0L, 1L),
      Row(1L, 2L, 0L)
    ))
  }

  test("vertex queries") {
    val vertices = g.find("(a)")
    assert(vertices.columns === Array("a"))
    assert(vertices.select("a.id", "a.attr").collect().toSet
      === v.select("id", "attr").collect().toSet)

    val empty = g.find("()")
    assert(empty.collect() === Array.empty)
  }

  test("triplets") {
    val triplets = g.find("(u)-[]->(v)")

    assert(triplets.columns === Array("u", "v"))
    assert(triplets.select("u.id", "u.attr", "v.id", "v.attr").collect().toSet === Set(
      Row(0L, "a", 1L, "b"),
      Row(1L, "b", 2L, "c"),
      Row(2L, "c", 3L, "d"),
      Row(2L, "c", 0L, "a")
    ))
  }

  test("negation") {
    val fof = g.find("(u)-[]->(v); (v)-[]->(w); !(u)-[]->(w); !(w)-[]->(u)")
      .select("u.id", "v.id", "w.id")

    assert(fof.collect().toSet === Set(Row(1L, 2L, 3L)))
  }

  test("named edges") {
    // edges whose destination leads nowhere
    val edges = g.find("()-[e]->(v); !(v)-[]->()")
      .select("e.src", "e.dst")
    assert(edges.collect().toSet === Set(Row(2L, 3L)))
  }

  test("named edge __tmp") {
    // named edge __tmp should not be removed if there is an anonymous edge
    val edges = g.find("()-[__tmp]->(v); (v)-[]->(w)")
    assert(edges.columns === Array("__tmp", "v", "w"))
  }

  test("find column order") {
    val fof = g.find("(u)-[e]->(v); (v)-[]->(w); !(u)-[]->(w); !(w)-[]->(u)")
    assert(fof.columns === Array("u", "e", "v", "w"))
    assert(fof.select("u.id", "v.id", "w.id").collect().toSet === Set(Row(1L, 2L, 3L)))

    val fv = g.find("(u)")
    assert(fv.columns === Array("u"))

    val fve = g.find("(u)-[e2]->()")
    assert(fve.columns === Array("u", "e2"))

    val fed = g.find("()-[e]->(w)")
    assert(fed.columns === Array("e", "w"))
  }

  test("stateful predicates via UDFs") {
    val chain4 = g.find("(a)-[ab]->(b); (b)-[bc]->(c); (c)-[cd]->(d)")

    // Using DataFrame operations, but not really operating in a stateful manner
    val chainWith2Friends = chain4.where(
      Seq("ab", "bc", "cd")
        .map(e => when(col(e)("relationship") === "friend", 1).otherwise(0))
        .reduce(_ + _) >= 2)

    assert(chainWith2Friends.count() === 4)
    chainWith2Friends.select("ab.relationship", "bc.relationship", "cd.relationship").collect()
      .foreach { case Row(ab: String, bc: String, cd: String) =>
        val numFriends = Seq(ab, bc, cd).map(r => if (r == "friend") 1 else 0).sum
        assert(numFriends >= 2)
      }

    // Operating in a stateful manner, where cnt is the state.
    def sumFriends(cnt: Column, relationship: Column): Column = {
      when(relationship === "friend", cnt + 1).otherwise(cnt)
    }
    val condition = Seq("ab", "bc", "cd").
      foldLeft(lit(0))((cnt, e) => sumFriends(cnt, col(e)("relationship")))
    val chainWith2Friends2 = chain4.where(condition >= 2)

    assert(chainWith2Friends.collect().toSet === chainWith2Friends2.collect().toSet)
  }

  /*
  // Join elimination will not work without Ankur's improved indexing.
  test("join elimination - simple") {
    import org.apache.spark.sql.catalyst.plans.logical.Join

    val edges = g.find("(u)-[e]->(v)", _.select("e_src", "e_dst"))
    val joins = edges.queryExecution.optimizedPlan.collect {
      case j: Join => j
    }

    assert(joins.isEmpty, s"joins was non-empty: ${joins.map(_.toString()).mkString("; ")}")
  }

  test("join elimination - with aliases") {
    import org.apache.spark.sql.catalyst.plans.logical.Join

    val edges = g.find("(u)-[]->(v)", _.select("u_id", "v_id"))
    println(edges.queryExecution.optimizedPlan)
    val joins = edges.queryExecution.optimizedPlan.collect {
      case j: Join => j
    }
    assert(joins.isEmpty, s"joins was non-empty: ${joins.map(_.toString()).mkString("; ")}")
  }
  */
}
