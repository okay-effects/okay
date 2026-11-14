package okay.http

/** okay-http's effect of a request, cut to what the audit reads: its case holds the continuation `answer`, a
  * function */
enum Fetch[X]:
  case Call(url: String, answer: String => X)
