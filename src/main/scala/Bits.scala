/*
 Copyright (c) 2011, 2012, 2013 The Regents of the University of
 California (Regents). All Rights Reserved.  Redistribution and use in
 source and binary forms, with or without modification, are permitted
 provided that the following conditions are met:

    * Redistributions of source code must retain the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer in the documentation and/or other materials
      provided with the distribution.
    * Neither the name of the Regents nor the names of its contributors
      may be used to endorse or promote products derived from this
      software without specific prior written permission.

 IN NO EVENT SHALL REGENTS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF
 REGENTS HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 REGENTS SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING, BUT NOT
 LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 A PARTICULAR PURPOSE. THE SOFTWARE AND ACCOMPANYING DOCUMENTATION, IF
 ANY, PROVIDED HEREUNDER IS PROVIDED "AS IS". REGENTS HAS NO OBLIGATION
 TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 MODIFICATIONS.
*/

package Chisel
import Node._
import ChiselError._

/* backward compatibility */
object Bits {
  def apply(x: Int): UInt = UInt(x);
  def apply(x: Int, width: Int): UInt = UInt(x, width);
  def apply(x: BigInt): UInt = UInt(x);
  def apply(x: BigInt, width: Int): UInt = UInt(x, width);
  def apply(x: String): UInt = UInt(x);
  def apply(x: String, width: Int): UInt = UInt(x, width);

  def apply(dir: IODirection = null, width: Int = -1): UInt = UInt(dir, width);

  def DC(width: Int): UInt = UInt.DC(width)
}


/** Base class for built-in Chisel types Bits and SInt. */
abstract class Bits extends Data with proc {
  Module.ioMap += ((this, Module.ioCount));
  Module.ioCount += 1;

  var canBeUsedAsDefault = false
  var dir: IODirection = null;

  def create(dir: IODirection, width: Int) {
    this.dir = dir;
    if(width > 0) {
      this.init("", width);
    } else {
      this.init("", widthOf(0))
    }
  }

  /** assigns this instance as the data type for *node*.
    */
  protected final def asTypeFor(node: Node): this.type = {
    this assign node
    this.setIsTypeNode
    if(!node.isInstanceOf[Literal]) node.nameHolder = this
    this
  }

  def fromInt(x: Int): this.type;

  def toSInt(): SInt = chiselCast(this){SInt()};

  def toUInt(): UInt = chiselCast(this){UInt()};

  def default: Node = if (inputs.length < 1 || inputs(0) == null) null else inputs(0);

  override def isLit: Boolean =
    if (inputs.length == 1 && inputs(0) != null) inputs(0).isLit
    else super.isLit

  override def litOf: Literal =
    if (inputs.length == 1 && inputs(0) != null) inputs(0).litOf
    else super.litOf

  // internal, non user exposed connectors
  def checkAssign(src: Node): Unit = {
    if (this.dir == INPUT && this.component == Module.current && this.isIo) {
      ChiselError.error({"assigning to your own input port " + this + " RHS: " + src});
    }
    if (this.dir == OUTPUT && this.component != Module.current &&
        src.component != null && src.component.parent != this.component) {
      ChiselError.error({"assigning to a non parent module's output port: " + this + " RHS: " + src});
    }
  }
  override def assign(src: Node): Unit = {
    checkAssign(src)
    if (inputs.isEmpty) inputs += src
    else ChiselError.error({"reassignment to Wire " + this + " with inputs " + this.inputs(0) + " RHS: " + src});
  }

  override def procAssign(src: Node): Unit = {
    checkAssign(src)
    if (inputs.isEmpty) updates += ((Module.current.whenCond, src))
    else ChiselError.error({"reassignment to Wire " + this + " with inputs " + this.inputs(0) + " RHS: " + src});
  }

  //code generation stuff

  override def setPseudoName (path : String, isNamingIo: Boolean) {
    if (isIo) {
      super.setPseudoName(path, isNamingIo)
    } else if (isTypeNode && comp != null) {
      comp setPseudoName (path, isNamingIo)
    } else if (isTypeNode && !inputs.isEmpty && !inputs.head.isLit) {
      inputs.head setPseudoName (path, isNamingIo)
    } else {
      super.setPseudoName(path, isNamingIo)
    } 
  }

  override def apply(name: String): Data = this

  override def flatten: Array[(String, Bits)] = Array((name, this));

  override def toString: String = {
    // XXX We cannot print the width here as it would computed the infered
    // width, hence change the computations. It might be possible to print
    // width_ but it seems to also have some underlying computations associated
    // to it.
    var str = (
      "/*" + (if (name != null && !name.isEmpty) name else "?")
        + (if (component != null) (" in " + component) else "") + "*/ "
        + getClass.getName + "("
        + (if (dir == INPUT) "INPUT, "
        else if (dir == OUTPUT) "OUTPUT, " else "")
        + "width=" + width_
        + ", connect to " + inputs.length + " inputs: (")
    var sep = ""
    for( i <- inputs ) {
      str = (str + sep + (if (i.name != null) i.name else "?")
        + "[" + i.getClass.getName + "]"
        + " in " + (if (i.component != null) i.component.getClass.getName else "?"))
      sep = ", "
    }
    str = str + "))"
    str
  }

  override def flip(): this.type = {
    assert(dir != null,
      ChiselError.error("Can't flip something that doesn't have a direction"))
    if (dir == INPUT) {
      dir = OUTPUT
    } else if(dir == OUTPUT) {
      dir = INPUT
    }
    this
  }

  override def asDirectionless(): this.type = {
    dir = null
    this
  }

  override def asInput(): this.type = {
    dir = INPUT
    this
  }

  override def asOutput(): this.type = {
    dir = OUTPUT
    this
  }

  override def isDirectionless: Boolean = {
    return dir == null
  }

  override def <>(src: Node) {
    if (dir == INPUT) {
      src match {
      case other: Bits =>
          if (other.dir == OUTPUT) { // input - output connections
          if(this.component == other.component && !isTypeNode) {//passthrough
            other assign this
          } else if (this.component.parent == other.component.parent || isTypeNode) { //producer - consumer
            if(other.inputs.length > 0 || other.updates.length > 0 || 
               other.component.isInstanceOf[BlackBox]) { // includes when a child is a blackbox
              this assign other // only do assignment if output has stuff connected to it
            }
          } else {
            ChiselError.error({"Undefined connections between " + this + " and " + other})
          }
        } else if (other.dir == INPUT) { // input <> input conections
            if(this.component == other.component.parent) { // parent <> child
              other assign this
            } else if(this.component.parent == other.component) { //child <> parent
              this assign other
            } else {
              ChiselError.error({"Can't connect Input " + this + " Input " + other})
            }
          } else { // io <> wire
            if(this.component == other.component) { //internal wire
              other assign this
            } else if(this.component.parent == other.component) { //external wire
              this assign other
            } else {
            ChiselError.error({"Connecting Input " + this + " to " + other})
            }
        }
      case default =>
        ChiselError.error({"Connecting Input " + this + " to IO without direction " + default})
      }
    } else if (dir == OUTPUT) {
      src match {
        case other: Bits  =>
          if (other.dir == INPUT) { // input - output connections
            if (this.component == other.component && !isTypeNode) { //passthrough
              this assign other;
            } else if (this.component.parent == other.component.parent || isTypeNode) { //producer - consumer
              if(this.inputs.length > 0 || this.updates.length > 0 ||
                 this.component.isInstanceOf[BlackBox]) { // includes when a child is a black box
                other assign this; // only do connection if I have stuff connected to me
              }
            } else {
              ChiselError.error({"Undefined connection between " + this + " and " + other})
            }
          } else if (other.dir == OUTPUT) { // output <> output connections
            if(this.component == other.component.parent) { // parent <> child
              if(other.inputs.length > 0 || other.updates.length > 0 || 
                 other.component.isInstanceOf[BlackBox]) { // includes when a child is a black box
                this assign other // only do connection if child is assigning to that output
              }
            } else if (this.component.parent == other.component) { // child <> parent
              if(this.inputs.length > 0 || this.updates.length > 0 || 
                 this.component.isInstanceOf[BlackBox]) { // includes when a child is a black box
                other assign this // only do connection if child (me) is assinging that output
              }
            } else if (this.isTypeNode && other.isTypeNode) { //connecting two type nodes together
              ChiselError.error("Ambiguous Connection of Two Nodes")
            } else if (this.isTypeNode){ // type <> output
              other assign this;
            } else if (other.isTypeNode){ // output <> type
              this assign other;
            } else {
              ChiselError.error({"Connecting Output " + this + " to Output " + other})
            }
          } else { // io <> wire
            if(this.component == other.component) { //output <> wire
              this assign other
            } else if(this.component.parent == other.component) {
              ChiselError.error({"Connecting Ouptut " + this + " to an external wire " + other})
            } else {
              ChiselError.error({"Connecting Output " + this + " to IO without direction " + other})
            }
          }
        case default =>
          ChiselError.error({"Connecting Output " + this + " to an IO withouth direction " + default})
      }
    }
    else {
      src match {
        case other: Bits =>
          if (other.dir == INPUT) { // wire <> input
            if(this.component == other.component) {
              this assign other
            } else if(this.component == other.component.parent) {
              other assign this
            } else {
              ChiselError.error({"Undefined connection between wire " + this + " and input " + other})
            }
          } else if (other.dir == OUTPUT) { //wire <> output
            if(this.component == other.component) { // internal wire
              other assign this
            } else if(this.component == other.component.parent) { // external wire
              this assign other
            } else {
              ChiselError.error({"Undefined connection between wire " + this + " and output " + other})
            }
          } else {
            this assign other
          }
        case default =>
          ChiselError.error({"Undefined connection between " + this + " and " + default})
      }
    }
  }

  override def setIsClkInput {
    isClkInput = true
    this assign clk
  }

  override def clone: this.type = {
    val res = this.getClass.newInstance.asInstanceOf[this.type];
    res.inferWidth = this.inferWidth
    res.width_ = this.width_;
    res.dir = this.dir;
    res
  }

  override def forceMatchingWidths {
    if(inputs.length == 1 && inputs(0).width != width) {
      inputs(0) = inputs(0).matchWidth(width)
    }
  }

  override def matchWidth(w: Int): Node =
    if (isLit && !litOf.isZ) Literal(litOf.value & ((BigInt(1) << w)-1), w)
    else super.matchWidth(w)

  // Operators
  protected final def newUnaryOp(opName: String): this.type = {
    fromNode(UnaryOp(this, opName))
  }

  protected final def newBinaryOp(right: Bits, opName: String): this.type = {
    fromNode(BinaryOp(this, right, opName))
  }

  protected final def newLogicalOp(right: Bits, opName: String): Bool = {
    Bool(OUTPUT).asTypeFor(LogicalOp(this, right, opName))
  }

  protected final def newReductionOp(opName: String): Bool = {
    Bool(OUTPUT).asTypeFor(ReductionOp(this, opName))
  }

  /** Assignment operator */
  override def :=[T <: Data](src: T): Unit = {
    src match {
      case b: Bits => {
        if(comp != null) {
          comp procAssign src;
        } else {
          this procAssign src;
        }
      }
      case any =>
        ChiselError.error("can't assign " + src.toString + " to Bits")
    }
  }


  // bitwise operators
  // =================

  /** Extract a single Bool at index *bit*.
    */
  final def apply(bit: Int): Bool = { Extract(this, bit){Bool()}};
  final def apply(bit: UInt): Bool = {Extract(this, bit){Bool()}};

  /** Extract a range of bits */
  final def apply(hi: Int, lo: Int): UInt = {Extract(this, hi, lo){UInt()}};
  final def apply(hi: UInt, lo: UInt): UInt = {Extract(this, hi, lo, -1){UInt()}};
  final def apply(range: (Int, Int)): UInt = this(range._1, range._2);

  def unary_~(): this.type   = newUnaryOp("~");
  def andR(): Bool           = newReductionOp("&");
  def orR(): Bool            = newReductionOp("|");
  def xorR(): Bool           = newReductionOp("^");
  def != (b: Bits): Bool     = newLogicalOp(b, "!=");
  def & (b: Bits): this.type = newBinaryOp(b, "&");
  def | (b: Bits): this.type = newBinaryOp(b, "|");
  def ^ (b: Bits): this.type = newBinaryOp(b, "^");
  
  def error(b: Bits): Bits = {
    throw new Exception("+ not defined on " + this.getClass + " and " + b.getClass)
    this
  }

  def + (b: Bits): Bits = {
    this match {
      case u0: UInt => {
        b match {
          case u1: UInt => u0 + u1
          case f1: SInt => u0 + f1
          case _ => error(b)
        }
      }
      case f0: SInt => {
        b match {
          case u1: UInt => f0 + u1
          case f1: SInt => f0 + f1
          case _ => error(b)
        }
      }
      case _ => error(b)
    }
  }
  def - (b: Bits): Bits = {
    this match {
      case u0: UInt => {
        b match {
          case u1: UInt => u0 - u1
          case f1: SInt => u0 - f1
          case _ => error(b)
        }
      }
      case f0: SInt => {
        b match {
          case u1: UInt => f0 - u1
          case f1: SInt => f0 - f1
          case _ => error(b)
        }
      }
      case _ => error(b)
    }
  }
  def * (b: Bits): Bits = {
    this match {
      case u0: UInt => {
        b match {
          case u1: UInt => u0 * u1
          case f1: SInt => u0 * f1
          case _ => error(b)
        }
      }
      case f0: SInt => {
        b match {
          case u1: UInt => f0 * u1
          case f1: SInt => f0 * f1
          case _ => error(b)
        }
      }
      case _ => error(b)
    }
  }
  def % (b: Bits): Bits = {
    this match {
      case u0: UInt => {
        b match {
          case u1: UInt => u0 % u1
          case f1: SInt => u0 % f1
          case _ => error(b)
        }
      }
      case f0: SInt => {
        b match {
          case u1: UInt => f0 % u1
          case f1: SInt => f0 % f1
          case _ => error(b)
        }
      }
      case _ => error(b)
    }
  }
  def / (b: Bits): Bits = {
    this match {
      case u0: UInt => {
        b match {
          case u1: UInt => u0 / u1
          case f1: SInt => u0 / f1
          case _ => error(b)
        }
      }
      case f0: SInt => {
        b match {
          case u1: UInt => f0 / u1
          case f1: SInt => f0 / f1
          case _ => error(b)
        }
      }
      case _ => error(b)
    }
  }

  override def ===[T <: Data](right: T): Bool = {
    right match {
      case b: Bits => newLogicalOp(b, "===");
      case _ =>
        this.asInstanceOf[Data] === right
    }
  }

  override def ##[T <: Data](right: T): this.type = {
    right match {
      case b: Bits => newBinaryOp(b, "##");
      case _ =>
        (this.asInstanceOf[Data] ## right).asInstanceOf[this.type];
    }
  }
}


object andR {
    def apply(x: Bits): Bool = x.andR
}

object orR {
    def apply(x: Bits): Bool = x.orR
}

object xorR {
    def apply(x: Bits): Bool = x.xorR
}
