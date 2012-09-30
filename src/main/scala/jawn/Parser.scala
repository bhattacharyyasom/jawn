package jawn

import scala.annotation.{switch, tailrec}

import java.io.FileInputStream
import java.nio.ByteBuffer

import debox.buffer.Mutable

trait Parser {

  // states
  @inline final val ARRBEG = 6
  @inline final val OBJBEG = 7
  @inline final val DATA = 1
  @inline final val KEY = 2
  @inline final val SEP = 3
  @inline final val ARREND = 4
  @inline final val OBJEND = 5

  def die(i: Int, msg: String) = sys.error("%s got %s (%d)" format (msg, at(i), i))

  def reset(i: Int): Int
  def all(i: Int): String
  def at(i: Int): Char
  def at(i: Int, j: Int): String
  def atEof(i: Int): Boolean
  def is(i: Int, c: Char): Boolean = at(i) == c
  def is(i: Int, j: Int, str: String): Boolean = at(i, j) == str

  final class PosBox(var pos: Int)

  // this code relies on parseLong/parseDouble to blow up for invalid inputs;
  // it does not try to exactly model the JSON input because we're not actually
  // going to "build" the numbers ourselves. it just needs to be sure that for
  // valid JSON we will find the right "number region".
  def parseNum(i: Int): (Value, Int) = {
    var j = i
    var c = at(j)

    if (c == '-') { j += 1; c = at(j) }
    while ('0' <= c && c <= '9') { j += 1; c = at(j) }

    if (c == '.' || c == 'e' || c == 'E') {
      j += 1
      c = at(j)
      while ('0' <= c && c <= '9' || c == '+' || c == '-' || c == 'e' || c == 'E') {
        j += 1
        c = at(j)
      }
      (DoubleNum(java.lang.Double.parseDouble(at(i, j))), j)
    } else if (j - i < 19) {
      (LongNum(java.lang.Long.parseLong(at(i, j), 10)), j)
    } else {
      (DoubleNum(java.lang.Double.parseDouble(at(i, j))), j)
    }
  }
  
  // used to parse the 4 hex digits from "\u1234" (i.e. "1234")
  final def descape(s: String) = java.lang.Integer.parseInt(s, 16).toChar

  // TODO: try using debox.buffer.Mutable + new String(arr, i, len)
  // instead of StringBuilder
  final def parseString(i: Int): (String, Int) = {
    if (at(i) != '"') sys.error("argh")
    val sb = new StringBuilder
    var j = i + 1
    var c = at(j)
    while (c != '"') {
      if (c == '\\') {
        (at(j + 1): @switch) match {
          case 'b' => { sb.append('\b'); j += 2 }
          case 'f' => { sb.append('\f'); j += 2 }
          case 'n' => { sb.append('\n'); j += 2 }
          case 'r' => { sb.append('\r'); j += 2 }
          case 't' => { sb.append('\t'); j += 2 }

          // if there's a problem then descape will explode
          case 'u' => { sb.append(descape(at(j + 2, j + 6))); j += 6 }

          // permissive: let any escaped char through, not just ", / and \
          case c2 => { sb.append(c2); j += 2 }
        }
      } else if (java.lang.Character.isHighSurrogate(c)) {
        // this case dodges the situation where we might incorrectly parse the
        // second Char of a unicode code point.
        sb.append(c)
        sb.append(at(j + 1))
        j += 2
      } else {
        // this case is for "normal" code points that are just one Char.
        sb.append(c)
        j += 1
      }
      j = reset(j)
      c = at(j)
    }
    (sb.toString, j + 1)
  }

  def parseTrue(i: Int) =
    if (is(i, i + 4, "true")) True else die(i, "expected true")

  def parseFalse(i: Int) =
    if (is(i, i + 5, "false")) False else die(i, "expected false")

  def parseNull(i: Int) =
    if (is(i, i + 4, "null")) Null else die(i, "expected null")

  def parse(i: Int): Value = (at(i): @switch) match {
    case ' ' => parse(i + 1)
    case '\t' => parse(i + 1)
    case '\n' => parse(i + 1)

    case '[' => rparse(ARRBEG, i + 1, new ArrContext :: Nil)
    case '{' => rparse(OBJBEG, i + 1, new ObjContext :: Nil)

    case '-' | '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' =>
      try {
        LongNum(java.lang.Long.parseLong(all(i)))
      } catch {
        case e:NumberFormatException =>
          DoubleNum(java.lang.Double.parseDouble(all(i)))
      }

    case '"' =>
      val (str, j) = parseString(i)
      if (atEof(j)) Str(str) else die(j, "expected eof")

    case 't' =>
      if (atEof(i + 4)) parseTrue(i) else die(i + 4, "expected eof")

    case 'f' =>
      if (atEof(i + 5)) parseFalse(i) else die(i + 5, "expected eof")

    case 'n' =>
      if (atEof(i + 4)) parseNull(i) else die(i + 4, "expected eof")

    case _ =>
      die(i, "expected json value")
  }

  @tailrec
  final def rparse(state: Int, j: Int, stack: List[Context]): Container = {
    val i = reset(j)
    (state: @switch) match {
      case DATA => (at(i): @switch) match {
        case ' ' => rparse(state, i + 1, stack)
        case '\t' => rparse(state, i + 1, stack)
        case '\n' => rparse(state, i + 1, stack)

        case '[' => rparse(ARRBEG, i + 1, new ArrContext :: stack)
        case '{' => rparse(OBJBEG, i + 1, new ObjContext :: stack)

        case '-' | '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' =>
          val (n, j) = parseNum(i)
          val ctxt = stack.head
          ctxt.add(n)
          rparse(if (ctxt.isObj) OBJEND else ARREND, j, stack)

        case '"' =>
          val (str, j) = parseString(i)
          val ctxt = stack.head
          ctxt.add(Str(str))
          rparse(if (ctxt.isObj) OBJEND else ARREND, j, stack)

        case 't' =>
          val ctxt = stack.head
          ctxt.add(parseTrue(i))
          rparse(if (ctxt.isObj) OBJEND else ARREND, i + 4, stack)

        case 'f' =>
          val ctxt = stack.head
          ctxt.add(parseFalse(i))
          rparse(if (ctxt.isObj) OBJEND else ARREND, i + 5, stack)

        case 'n' =>
          val ctxt = stack.head
          ctxt.add(parseNull(i))
          rparse(if (ctxt.isObj) OBJEND else ARREND, i + 4, stack)
      }

      case KEY => (at(i): @switch) match {
        case ' ' => rparse(state, i + 1, stack)
        case '\t' => rparse(state, i + 1, stack)
        case '\n' => rparse(state, i + 1, stack)

        case '"' =>
          val (str, j) = parseString(i)
          stack.head.asInstanceOf[ObjContext].addKey(str)
          rparse(SEP, j, stack)

        case _ => die(i, "expected \"")
      }

      case ARRBEG => (at(i): @switch) match {
        case ' ' => rparse(state, i + 1, stack)
        case '\t' => rparse(state, i + 1, stack)
        case '\n' => rparse(state, i + 1, stack)

        case ']' => stack match {
          case ctxt1 :: Nil =>
            ctxt1.finish
          case ctxt1 :: ctxt2 :: tail =>
            ctxt2.add(ctxt1.finish)
            rparse(if (ctxt2.isObj) OBJEND else ARREND, i + 1, ctxt2 :: tail)
          case _ =>
            sys.error("invalid stack")
        }

        case _ => rparse(DATA, i, stack)
      }

      case OBJBEG => (at(i): @switch) match {
        case ' ' => rparse(state, i + 1, stack)
        case '\t' => rparse(state, i + 1, stack)
        case '\n' => rparse(state, i + 1, stack)

        case '}' => stack match {
          case ctxt1 :: Nil =>
            ctxt1.finish
          case ctxt1 :: ctxt2 :: tail =>
            ctxt2.add(ctxt1.finish)
            rparse(if (ctxt2.isObj) OBJEND else ARREND, i + 1, ctxt2 :: tail)
          case _ =>
            sys.error("invalid stack")
        }

        case _ => rparse(KEY, i, stack)
      }

      case SEP => (at(i): @switch) match {
        case ' ' => rparse(state, i + 1, stack)
        case '\t' => rparse(state, i + 1, stack)
        case '\n' => rparse(state, i + 1, stack)

        case ':' => rparse(DATA, i + 1, stack)

        case _ => die(i, "expected :")
      }

      case ARREND => (at(i): @switch) match {
        case ' ' => rparse(state, i + 1, stack)
        case '\t' => rparse(state, i + 1, stack)
        case '\n' => rparse(state, i + 1, stack)

        case ',' => rparse(DATA, i + 1, stack)

        case ']' => stack match {
          case ctxt1 :: Nil =>
            ctxt1.finish
          case ctxt1 :: ctxt2 :: tail =>
            ctxt2.add(ctxt1.finish)
            rparse(if (ctxt2.isObj) OBJEND else ARREND, i + 1, ctxt2 :: tail)
          case _ =>
            sys.error("invalid stack")
        }

        case _ => die(i, "expected ] or ,")
      }

      case OBJEND => (at(i): @switch) match {
        case ' ' => rparse(state, i + 1, stack)
        case '\t' => rparse(state, i + 1, stack)
        case '\n' => rparse(state, i + 1, stack)

        case ',' => rparse(KEY, i + 1, stack)

        case '}' => stack match {
          case ctxt1 :: Nil =>
            ctxt1.finish
          case ctxt1 :: ctxt2 :: tail =>
            ctxt2.add(ctxt1.finish)
            rparse(if (ctxt2.isObj) OBJEND else ARREND, i + 1, ctxt2 :: tail)
          case _ =>
            sys.error("invalid stack")
        }
        
        case _ => die(i, "expected } or ,")
      }
    }
  }
}

object Parser {
  def parse(s: String): Value = new StringParser(s).parse(0)
}

final class StringParser(s: String) extends Parser {
  def reset(i: Int): Int = i
  def at(i: Int): Char = s.charAt(i)
  def at(i: Int, j: Int): String = s.substring(i, j)
  def atEof(i: Int) = i == s.length
  def all(i: Int) = s.substring(i)
}

final class PathParser(name: String) extends Parser {
  @inline final def bufsize = 1048576 // 1M buffer
  @inline final def mask = bufsize - 1

  val f = new FileInputStream(name)
  val ch = f.getChannel()

  var curr = new Array[Byte](bufsize)
  var next = new Array[Byte](bufsize)

  var bcurr = ByteBuffer.wrap(curr)
  var bnext = ByteBuffer.wrap(next)

  var ncurr = ch.read(bcurr)
  var nnext = ch.read(bnext)
  
  def swap() {
    var tmp = curr; curr = next; next = tmp
    var btmp = bcurr; bcurr = bnext; bnext = btmp
    var ntmp = ncurr; ncurr = nnext; nnext = ntmp
  }

  def reset(i: Int): Int = {
    if (i >= bufsize) {
      bcurr.clear()
      swap()
      nnext = ch.read(bnext)
      i - bufsize
    } else {
      i
    }
  }

  def at(i: Int): Char = if (i < bufsize)
    curr(i).toChar
  else
    next(i & mask).toChar

  def at(i: Int, k: Int): String = {
    val len = k - i
    val arr = new Array[Byte](len)

    if (k <= bufsize) {
      System.arraycopy(curr, i, arr, 0, len)
    } else {
      val mid = bufsize - i
      System.arraycopy(curr, i, arr, 0, mid)
      System.arraycopy(next, 0, arr, mid, k - bufsize)
    }
    new String(arr)
  }

  def atEof(i: Int) = if (i < bufsize) i >= ncurr else i >= nnext
  def all(i: Int) = {
    var j = i
    val sb = new StringBuilder
    while (!atEof(j)) {
      if (ncurr == bufsize) {
        sb.append(at(j, bufsize))
        j = reset(bufsize)
      } else {
        sb.append(at(j, ncurr))
        j = reset(ncurr)
      }
    }
    sb.toString
  }
}
