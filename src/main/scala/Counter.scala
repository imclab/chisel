/*
 Copyright (c) 2011, 2012, 2013, 2014 The Regents of the University of
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

import scala.collection.mutable.HashMap
import scala.math.pow

trait CounterBackend extends Backend {
  val firedPins = new HashMap[Module, Bool]
  val daisyIns = new HashMap[Module, UInt]
  val daisyOuts = new HashMap[Module, DecoupledIO[UInt]]
  val daisyCtrls = new HashMap[Module, Bits]
  val counterCopy = new HashMap[Module, Bool]
  val counterRead = new HashMap[Module, Bool]
  val decoupledPins = new HashMap[Node, Bits]

  var counterIdx = -1

  override def backannotationTransforms {
    super.backannotationTransforms

    transforms += ((c: Module) => c bfs (_.addConsumers))

    transforms += ((c: Module) => decoupleTarget(c))
    transforms += ((c: Module) => connectDaisyPins(c))
    transforms += ((c: Module) => generateCounters(c))
    transforms += ((c: Module) => generateDaisyChains(c))

    transforms += ((c: Module) => c.addClockAndReset)
    transforms += ((c: Module) => gatherClocksAndResets)
    transforms += ((c: Module) => connectResets)
    transforms += ((c: Module) => c.inferAll)
    transforms += ((c: Module) => c.forceMatchingWidths)
    transforms += ((c: Module) => c.removeTypeNodes)
    transforms += ((c: Module) => collectNodesIntoComp(initializeDFS))
  }

  def emitCounterIdx = {
    counterIdx = counterIdx + 1
    counterIdx
  }

  def addPin(m: Module, pin: Data, name: String) {
    // assign component
    pin.component = m
    // a hack to index pins by their name
    pin setName name
    // include in io
    pin.isIo = true
    (m.io) match {
      case io: Bundle => io += pin
    }
    // set its real name
    pin setName ("io_" + name)

    // for complex pins
    pin match {
      case dio: DecoupledIO[_] => {
        dio.ready.component = m
        dio.valid.component = m
        dio.bits.component = m
        dio.ready.isIo = true
        dio.valid.isIo = true
        dio.bits.isIo = true
        dio.ready setName ("io_" + name + "_ready")
        dio.valid setName ("io_" + name + "_valid")
        dio.bits setName ("io_" + name + "_bits")
      }
      case vio: ValidIO[_] => {
        vio.valid.component = m
        vio.bits.component = m
        vio.valid.isIo = true
        vio.bits.isIo = true
        vio.valid setName ("io_" + name + "_valid")
        vio.bits setName ("io_" + name + "_bits")
      }
      case _ =>
    } 
  }

  def wirePin(pin: Data, input: Node) {
    if (pin.inputs.isEmpty) pin.inputs += input
    else pin.inputs(0) = input
  }

  def addReg(m: Module, outType: Bits, name: String = "", updates: Map[Bool, Node] = Map()) {
    val reg = outType.comp match {
      case r: Reg => r
    }
    // assign component
    reg.component = m

    // assign clock
    reg.clock = m.clock

    // assign name
    if (name != "") reg setName name

    // set enable signal
    reg.isEnable = !updates.isEmpty
    for ((cond, value) <- updates) {
      reg.enable = reg.enable || cond
    }

    // add updates
    reg.updates ++= updates

    // genreate muxes
    reg genMuxes reg

    // assign reset
    if (reg.isReset) 
      reg.inputs += m.reset
  }

  def connectConsumers(input: Node, via: Node) {
    for (consumer <- input.consumers) {
      val idx = consumer.inputs indexOf input
      consumer.inputs(idx) = via
    }
  }

  def decoupleTarget(c: Module) {
    ChiselError.info("[CounterBackend] target decoupling")

    val clksPin = Decoupled(UInt(width = 32)).flip
    val clksReg = Reg(init = UInt(0, 32))
    val fired = clksReg.orR
    val notFired = !fired
    val firedPin = Bool(OUTPUT)

    addReg(c, clksReg, "clks", Map(
      (clksPin.valid && notFired) -> clksPin.bits,
      fired                       -> (clksReg - UInt(1))
    ))

    // For the input and output pins of the top component
    // insert buffers so that their values are avaiable
    // after the target is stalled
    for ((n, pin) <- c.io.flatten) {
      pin match {
        case in: Bits if in.dir == INPUT => {
          val in_reg = Reg(UInt())
          addReg(c, in_reg, in.pName + "_buf",
            Map(notFired -> in)
          )
          connectConsumers(in, in_reg)
          decoupledPins(in) = in_reg
        }
        case out: Bits if out.dir == OUTPUT => {
          val out_reg = Reg(UInt())
          addReg(c, out_reg, out.pName + "_buf", 
            Map(fired -> out.inputs.head)
          )
          wirePin(out, out_reg)
          decoupledPins(out) = out_reg
        }
      }
    }

    // pins for the clock counter & fire signal
    addPin(c, clksPin, "clks")
    addPin(c, firedPin, "fire")
    wirePin(firedPin, fired)
    wirePin(clksPin.ready, notFired)
    firedPins(c) = fired

    val queue = new scala.collection.mutable.Queue[Module]
    queue enqueue c

    while (!queue.isEmpty) {
      val top = queue.dequeue

      // Make all delay nodes be enabled by the fire signal
      for (node <- top.nodes) {
        node match {
          // For Reg, different muxes are generated by different backend
          case reg: Reg if this.isInstanceOf[VerilogBackend] && 
                           !Module.isBackannotating => {
            val enable = firedPins(top) && reg.enable
            reg.inputs(reg.enableIndex) = enable.getNode
          }
          case reg: Reg => {
            reg.inputs(0) = Multiplex(firedPins(top), reg.next, reg)
          }
          case mem: Mem[_] => {
            for (write <- mem.writeAccesses) {
              val en = Bool()
              val newEn = firedPins(top) && en
              if (Module.isBackannotating)
                newEn.getNode setName (en.getNode.pName + "_fire")
              wirePin(en, write.inputs(1))
              write.inputs(1) = newEn
            }
          }
          case _ =>
        }
      }

      // insert the fire signal pin to children
      for (child <- top.children) {
        val firedPin = Bool(INPUT)
        addPin(child, firedPin, "fire")
        wirePin(firedPin, firedPins(top))
        firedPins(child) = firedPin
        queue enqueue child
      }
    }
  }

  // Connect daisy pins of hierarchical modules
  def connectDaisyPins(c: Module) {
    ChiselError.info("[CounterBackend] connect daisy pins")

    val queue = new scala.collection.mutable.Queue[Module]
    queue enqueue c

    while (!queue.isEmpty) {
      val m = queue.dequeue

      // add daisy pins and wire them to its parent's daisy pins
      val daisyIn = UInt(INPUT, 32)
      val daisyOut = Decoupled(UInt(width = 32))
      val daisyCtrl = UInt(INPUT, 1)
      val daisyFire = daisyOut.ready && !firedPins(m)
      val copy = daisyFire && daisyCtrl === Bits(0)
      val read = daisyFire && daisyCtrl === Bits(1)
      copy.getNode setName "copy"
      read.getNode setName "read"
  
      addPin(m, daisyOut,  "daisy_out")
      addPin(m, daisyCtrl, "daisy_ctrl")
      if (m != c) {
        addPin(m, daisyIn, "daisy_in")
        wirePin(daisyOut.ready, daisyOuts(m.parent).ready)
        wirePin(daisyCtrl,      daisyCtrls(m.parent))
      }
      wirePin(daisyOut.valid,   daisyFire)

      // The top component has no daisy input
      daisyIns(m)    = if (m ==c) UInt(0) else daisyIn
      daisyOuts(m)   = daisyOut
      daisyCtrls(m)  = daisyCtrl
      counterCopy(m) = copy
      counterRead(m) = read

      // visit children
      m.children map (queue enqueue _)
   }

    queue enqueue c

    while (!queue.isEmpty) {
      val m = queue.dequeue

      if (!m.children.isEmpty) {
        val head = m.children.head
        val last = m.children.last

        // If the component has its children and no signals for counters,
        // its first child's daisy output is connected to its daisy output
        if (m.signals.isEmpty) {
          if (m == c) {
            // For the top component, the shaodw buffer is inserted
            // between the outpins 
            // so that the first counter value would not be missed
            // when shadow counter values are shifted
            val buf = Reg(next = daisyOuts(head).bits)
            addReg(m, buf, "shadow_buf")
            wirePin(daisyOuts(m).bits, buf)            
          } else {
            // Otherwise, just connect them
            wirePin(daisyOuts(m).bits, daisyOuts(head).bits)
          }
        }

        for (i <- 0 until m.children.size - 1) {
          val cur = m.children(i)
          val next = m.children(i+1)
          // the current child's daisy input <- the next child's daisy output
          wirePin(daisyIns(cur), daisyOuts(next).bits)
        }

        // the last child's daisy input <- the module's diasy input
        wirePin(daisyIns(last), daisyIns(m))
      } else if (m.signals.isEmpty) {
        // No children & no singals for counters
        // the daisy output <- the daisy input
        wirePin(daisyOuts(m).bits, daisyIns(m))
      }

      m.children map (queue enqueue _)
    }
  }

  def generateCounters (c: Module) {
    ChiselError.info("[CounterBackend] generate counters")

    val queue = new scala.collection.mutable.Queue[Module]
    queue enqueue c
 
    while (!queue.isEmpty) {
      val m = queue.dequeue

      for (signal <- m.signals) {
        val signalWidth = signal.width
        val signalValue = 
          if (decoupledPins contains signal) decoupledPins(signal) 
          else UInt(signal)
        // Todo: configurable counter width
        val counter = Reg(Bits(width = 32))
        val shadow  = Reg(Bits(width = 32))
        signal.counter = counter
        signal.shadow = shadow
        signal.cntrIdx = emitCounterIdx

        val counterValue = {
          // Signal
          if (signalWidth == 1) {
            counter + signalValue
          // Bus -> hamming distance
          } else {
            val buffer = Reg(UInt(width = signalWidth))
            val xor = signalValue ^ buffer
            xor.inferWidth = (x: Node) => signalWidth
            val hd = PopCount(xor)
            addReg(m, buffer, "buffer_%d".format(signal.cntrIdx), 
              Map(firedPins(m) -> signalValue)
            )
            counter + hd
          }
        }
        counterValue.getNode.component = m
        counterValue.getNode setName "c_value_%d".format(signal.cntrIdx)

        /****** Activity Counter *****/
        // 1) fire signal -> increment counter
        // 2) 'copy' control signal when the target is stalled -> reset
        addReg(m, counter, "counter_%d".format(signal.cntrIdx), Map(
          firedPins(m)   -> counterValue,
          counterCopy(m) -> Bits(0)
        ))

        // for debugging
        if (Module.isBackannotating) {
          signal setName "signal_%d_%s".format(counterIdx, signal.pName)
        }
      }

      // visit children
      m.children map (queue enqueue _)
    }
  }  
 
  def generateDaisyChains(c: Module) {
    ChiselError.info("[CounterBackend] daisy chaining")
 
    val queue = new scala.collection.mutable.Queue[Module] 
    queue enqueue c

    // Daisy chaining
    while (!queue.isEmpty) {
      val m = queue.dequeue 
      if (!m.signals.isEmpty) {
        val head = m.signals.head
        val last = m.signals.last
        if (m == c) {
          // For the top component, the shaodw buffer is inserted
          // at the frontend of the daisy chain
          // so that the first counter value would not be missed
          // when shadow counter values are shifted
          val buf = Reg(next = head.shadow)
          addReg(m, buf, "shadow_buf")
          wirePin(daisyOuts(m).bits, buf)
        } else {
          // Ohterwise, just connect them
          wirePin(daisyOuts(m).bits, head.shadow)
        }

        for (i <- 0 until m.signals.size - 1) {
          val cur = m.signals(i)
          val next = m.signals(i+1)
          /****** Shaodw Counter *****/
          // 1) 'copy' control signal -> copy counter values from the activity counter
          // 2) 'read' control signal -> shift counter values from the next shadow counter
          addReg(m, cur.shadow, "shadow_%d".format(cur.cntrIdx), Map(
            counterCopy(m) -> cur.counter,
            counterRead(m) -> next.shadow
          ))
      
          // Signals are collected in order
          // so that counter values are verified 
          // and power numbers are calculated
          Module.signals += cur
        }

        // For the last counter of the daisy chain
        // 1) the module has chilren -> its first child's daisy output
        // 2) otherwise -> its daisy input 
        val lastread = 
          if (m.children.isEmpty) daisyIns(m) 
          else daisyOuts(m.children.head).bits
        addReg(m, last.shadow, "shadow_%d".format(last.cntrIdx), Map(
          counterCopy(m) -> last.counter,
          counterRead(m) -> lastread
        ))

        Module.signals += m.signals.last
      }

      // visit children
      m.children map (queue enqueue _)
    }
  }
}

class CounterCppBackend extends CppBackend with CounterBackend
class CounterVBackend extends VerilogBackend with CounterBackend

abstract class CounterTester[+T <: Module](c: T, isTrace: Boolean = true) extends Tester(c, isTrace) {
  val prevPeeks = new HashMap[Node, BigInt]
  val counts = new HashMap[Node, BigInt]

  // compute the hamming distance of 'a' and 'b'
  def calcHD(a: BigInt, b: BigInt) = {
    var xor = a ^ b
    var hd: BigInt = 0
    while (xor > 0) {
      hd = hd + (xor & 1)
      xor = xor >> 1
    }
    hd
  }

  // proceed 'n' clocks
  def clock (n: Int) {
    val clk = emulatorCmd("clock %d".format(n))
    if (isTrace) println("  CLOCK %s".format(clk))
  }

  override def reset(n: Int = 1) {
    super.reset(n)
    if (t >= 1) {
      // reset prevPeeks
      for (signal <- Module.signals ; if signal.width > 1) {
        prevPeeks(signal) = 0
      }
    }
  }

  // proceed 'n' clocks
  def pokeClks (n: Int) {
    val clks = c.io("clks") match {
      case dio: DecoupledIO[_] => dio
    }
    val fire = c.io("fire") match {
      case bool: Bool => bool
    }
    // Wait until the clock counter is ready
    // (the target is stalled)
    while(peek(clks.ready) == 0) {
      clock(1)
    }
    // Set the clock counter
    pokeBits(clks.bits, n)
    pokeBits(clks.valid, 1)
    clock(1)
    pokeBits(clks.valid, 0)
  }

  // i = 0 -> daisy copy
  // i = 1 -> daisy read 
  def peekDaisy (i: Int) {
    val daisyCtrl = c.io("daisy_ctrl") match {
      case bits: Bits => bits
    }
    val daisyOut = c.io("daisy_out") match {
      case dio: DecoupledIO[_] => dio
    }
    // request the daisy output
    // until it is valid
    do {
      poke(daisyCtrl, i)
      poke(daisyOut.ready, 1)
      clock(1)
    } while (peek(daisyOut.valid) == 0)
    poke(daisyOut.ready, 0)
  }

  // Do you believe the diasy output
  def checkDaisy(count: BigInt) {
    val daisyOut = c.io("daisy_out") match {
      case dio: DecoupledIO[_] => dio
    }
    val daisyOutBits = daisyOut.bits match {
      case bits: Bits => bits
    }
    expect(daisyOutBits, count)
  }

  // Show me the current status of the daisy chain
  def showCurrentChain {
    if (isTrace) {
      println("--- CURRENT CHAIN ---")
      for (s <- Module.signals) {
        peek(s.shadow)
      }
      println("---------------------")
    }
  }

  override def step (n: Int = 1) { 
    if (isTrace) {
      println("-------------------------")
      println("| Counter Strcture Step |")
      println("-------------------------")
    }

    for (signal <- Module.signals) {
      counts(signal) = 0
    }

    // set clock register
    pokeClks(n)

    // run the target until it is stalled
    if (isTrace) println("*** RUN THE TAREGT / READ SIGNAL VALUES ***")
    for (i <- 0 until n) {
      for (signal <- Module.signals) {
        val curPeek = peekBits(signal)
        if (signal.width == 1) {
          // increment by the signal's value
          counts(signal) += curPeek
        } else {
          // increment by the hamming distance
          counts(signal) += calcHD(curPeek, prevPeeks(signal))
          prevPeeks(signal) = curPeek
        }
      }
      clock(1)
    }

    // Check activity counter values 
    if (isTrace) println("*** CHECK COUNTER VALUES ***")
    for (signal <- Module.signals) {
      expect(signal.counter, counts(signal))
    }

    if (isTrace) println("*** Daisy Copy ***")
    // Copy activity counter values to shadow counters
    peekDaisy(0)
    showCurrentChain

    for (signal <- Module.signals) {
      if (isTrace) println("*** Daisy Read ***")
      // Read out the daisy chain
      peekDaisy(1)
      showCurrentChain
      // Check the daisy output
      checkDaisy(counts(signal))
    }

    t += n
  }

  // initialization
  for (signal <- Module.signals ; if signal.width > 1) {
    prevPeeks(signal) = 0
  }
}

// Counter backend, which deals with FPGA Counter wrappers
trait CounterWrapperBackend extends CounterBackend {
  override def getPseudoPath(c: Module, delim: String = "/"): String = {
    if (!(c.parent == null)) {
      c.parent match {
        case _: CounterWrapper => extractClassName(c)
        case _ => getPseudoPath(c.parent, delim) + delim + c.pName
      }
    } else ""
  }

  override def setPseudoNames(c: Module) {
    c match {
      case m: CounterWrapper => super.setPseudoNames(m.top)
    }
  }

  override def decoupleTarget(c: Module) {
    c match {
      case m: CounterWrapper => {
        super.decoupleTarget(m.top)
        // write 4 => clks
        val clks = m.top.io("clks") match {
          case dio: DecoupledIO[_] => dio
        }
        val fire = m.top.io("fire") match {
          case bool: Bool => bool
        }
        wirePin(clks.bits,  m.io.in.bits)
        wirePin(clks.valid, m.wen(4))
        wirePin(m.wready(4), clks.ready)
      }
    }
  }

  override def connectDaisyPins(c: Module) {
    c match {
      case m: CounterWrapper => {
        super.connectDaisyPins(m.top)
        // read 4 => daisy outputs
        wirePin(m.rdata(4),  daisyOuts(m.top).bits)
        wirePin(m.rvalid(4), daisyOuts(m.top).valid)
        wirePin(daisyOuts(m.top).ready, m.ren(4))
        val daisyCtrlBits = m.io("addr") match {
          case bits: Bits => 
            if (m.conf.daisyCtrlWidth == 1) bits(m.conf.addrWidth - 1)
            else bits(m.conf.addrWidth - 1, m.conf.addrWidth - m.conf.daisyCtrlWidth)
        }
        wirePin(daisyCtrls(m.top), daisyCtrlBits)        
      }
    }
  }
 
  override def generateCounters(c: Module) {
    c match {
      case m: CounterWrapper => super.generateCounters(m.top)
    }
  }

  override def generateDaisyChains(c: Module) {
    c match {
      case m: CounterWrapper => super.generateDaisyChains(m.top)
    }
  }
}

class CounterWBackend extends CppBackend with CounterWrapperBackend
class CounterFPGABackend extends FPGABackend with CounterWrapperBackend

case class CounterConfiguration(
  addrWidth: Int = 5,
  dataWidth: Int = 32,
  daisyCtrlWidth: Int = 1,
  counterWidth: Int = 32) 
{
  val n = pow(2, addrWidth - daisyCtrlWidth).toInt
}

// IO port connected to AXI bus
class CounterWrapperIO(conf: CounterConfiguration) extends Bundle {
  val in = Decoupled(Bits(width = conf.dataWidth)).flip
  val out = Decoupled(Bits(width = conf.dataWidth))
  val addr = Bits(INPUT, conf.addrWidth)
}

// FPGA Counter Wrapper
// 'top' module should be specified
abstract class CounterWrapper(val conf: CounterConfiguration) extends Module {
  val io = new CounterWrapperIO(conf)
  def top: Module

  def wen(i: Int) = io.in.valid && io.addr(log2Up(conf.n)-1, 0) === UInt(i)
  def ren(i: Int) = io.out.ready && io.addr(log2Up(conf.n)-1, 0) === UInt(i)
  val rdata = Vec.fill(conf.n){Bits(width = conf.dataWidth)}
  val rvalid = Vec.fill(conf.n){Bool()}
  val wready = Vec.fill(conf.n){Bool()}

  io.in.ready  := wready(io.addr)
  io.out.valid := rvalid(io.addr)
  io.out.bits  := rdata(io.addr)
}

abstract class CounterWrapperTester[+T <: CounterWrapper](c: T, isTrace: Boolean = true) extends CounterTester(c, isTrace) {
  // poke 'bits' to the address 'addr'
  def pokeAddr(addr: BigInt, bits: BigInt) {
    do {
      poke(c.io.addr, addr)
      clock(1)
    } while (peek(c.io.in.ready) == 0)

    poke(c.io.in.bits, bits)
    poke(c.io.in.valid, 1)
    clock(1)
    poke(c.io.in.valid, 0)
  }

  // peek the signal from the address 'addr'
  def peekAddr(addr: BigInt) = {
    do {
      poke(c.io.addr, addr)
      poke(c.io.out.ready, 1)
      clock(1)
    } while (peek(c.io.out.valid) == 0)

    peek(c.io.out.bits)
  }

  // compare the signal value from the address 'addr' with 'expected'
  def expectAddr(addr: BigInt, expected: BigInt) = {
    do {
      poke(c.io.addr, addr)
      poke(c.io.out.ready, 1)
      clock(1)
    } while (peek(c.io.out.valid) == 0)
   
    expect(c.io.out.bits, expected)
  }

  // poke 'n' clocks to the clock cycle
  // whose address is 4
  override def pokeClks (n: Int) {
    do {
      poke(c.io.addr, 4)
      clock(1)
    } while (peek(c.io.in.ready) == 0)
 
    pokeBits(c.io.in.bits, n)
    pokeBits(c.io.in.valid, 1)
    clock(1)
    pokeBits(c.io.in.valid, 0)
  }

  // read at 4 | 0 << 4 -> daisy copy
  // read at 4 | 1 << 4 -> daisy read
  override def peekDaisy (i: Int) {
    do {
      pokeBits(c.io.addr, 4 | i << 4)
      pokeBits(c.io.out.ready, 1)
      clock(1)
    } while (peek(c.io.out.valid) == 0)
  }

  override def checkDaisy(count: BigInt) {
    expect(c.io.out.bits, count)
  }
}
