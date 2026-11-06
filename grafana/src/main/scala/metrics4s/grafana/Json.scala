package metrics4s.grafana

enum Json:
  case Obj(fields: List[(String, Json)])
  case Arr(items: List[Json])
  case Str(value: String)
  case Num(value: BigDecimal)
  case Bool(value: Boolean)
  case Null

  def render: String =
    val out = StringBuilder()
    Json.write(this, out, 0)
    out.result()

  def field(name: String): Option[Json] =
    this match
      case Obj(fields) => fields.collectFirst { case (key, value) if key == name => value }
      case _           => None

object Json:
  def obj(fields: (String, Json)*): Json = Obj(fields.toList)
  def arr(items: Json*): Json            = Arr(items.toList)
  def str(value: String): Json           = Str(value)
  def num(value: Int): Json              = Num(BigDecimal(value))
  def num(value: Double): Json           = Num(BigDecimal(value))
  def bool(value: Boolean): Json         = Bool(value)

  private def write(json: Json, out: StringBuilder, indent: Int): Unit =
    json match
      case Obj(Nil)    => out ++= "{}"
      case Obj(fields) =>
        out ++= "{\n"
        fields.zipWithIndex.foreach { case ((key, value), index) =>
          pad(out, indent + 1)
          quote(key, out)
          out ++= ": "
          write(value, out, indent + 1)
          if index < fields.size - 1 then out += ','
          out += '\n'
        }
        pad(out, indent)
        out += '}'
      case Arr(Nil)    => out ++= "[]"
      case Arr(items)  =>
        out ++= "[\n"
        items.zipWithIndex.foreach { (value, index) =>
          pad(out, indent + 1)
          write(value, out, indent + 1)
          if index < items.size - 1 then out += ','
          out += '\n'
        }
        pad(out, indent)
        out += ']'
      case Str(value)  => quote(value, out)
      case Num(value)  => out ++= value.bigDecimal.stripTrailingZeros.toPlainString
      case Bool(value) => out ++= value.toString
      case Null        => out ++= "null"

  private def pad(out: StringBuilder, indent: Int): Unit = out ++= "  " * indent

  private def quote(value: String, out: StringBuilder): Unit =
    out += '"'
    value.foreach {
      case '"'                => out ++= "\\\""
      case '\\'               => out ++= "\\\\"
      case '\n'               => out ++= "\\n"
      case '\r'               => out ++= "\\r"
      case '\t'               => out ++= "\\t"
      case '\b'               => out ++= "\\b"
      case '\f'               => out ++= "\\f"
      case char if char < ' ' => out ++= f"\\u${char.toInt}%04x"
      case char               => out += char
    }
    out += '"'
