package okay

/** docs/queues.md's default channel, line for line (TestDocSnippets pins each line of the page to a line here or
 * in a library source; this line was pinned by okay2's TestChannel until okay2 was archived, 2026-11-24) */
class TestDocExamplesQueues extends munit.FunSuite with okay.testkit.Munit.Diagnosed {

  test("queues: the default channel is bounded and closing keeps what is buffered") {
    val c = Channel[Int](1024)
    assert(c.offer(42))
    note("offered 42 into a 1024 ring, closing")
    c.close()
    assertEquals(c.receiveBlocking(), Some(42))
    assertEquals(c.receiveBlocking(), None)
  }
}
