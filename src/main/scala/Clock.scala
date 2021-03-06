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

import scala.collection.mutable.ArrayBuffer

class Clock(reset: Bool = Module.implicitReset) extends Node {
  val stateElms = new ArrayBuffer[Node]
  Module.clocks += this
  init("", 1)

  var srcClock: Clock = null
  var initStr = ""

  // returns a reset pin connected to reset for the component in scope
  def getReset: Bool = {
    if (Module.compStack.length != 0) {
      Module.compStack.top.getResetPin(reset)
    } else {
      reset
    }
  }

  def * (x: Int): Clock = {
    val clock = new Clock(reset)
    clock.init("", 1)
    clock.srcClock = this
    clock.initStr = " * " + x + ";\n"
    clock
  }

  def / (x: Int): Clock = {
    val clock = new Clock(reset)
    clock.init("", 1)
    clock.srcClock = this
    clock.initStr = " / " + x + ";\n"
    clock
  }
}
