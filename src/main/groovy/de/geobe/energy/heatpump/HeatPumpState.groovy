/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2021.  Georg Beier. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package de.geobe.energy.heatpump
/**
 * Interface class to two relays controlling smart grid state of Ochsner heat pump.
 * According to document AA-FE_Einstellung_Smart_Grid_OTE3_4_ab_V5.8x_DE_20160302.pdf,
 * four states are supported, see HeatPumpState below. <br>
 * In our HW configuration, we use 2 toggle relays K1 and K2. When the program is stopped or not functional,
 * the heat pump shall run in NORMALOPERATION mode. We have the following truth table:<br>
 * | PIN21 = K1 | PIN43 = K2 | MODE<br>
 * |  contact   |    open    | NORMALOPERATION<br>
 * |  contact   |   contact  | PRECEDENCE<br>
 * |   open     |    open    | SUSPENDED<br>
 * |   open     |   contact  | ENFORCED<br>
 * This implies that K1 must use the switch-off contact, K2 the switch-on contact of the toggle relay<br>
 * Relays have inverse logic i.e. PinState.High => relay is off
 */

enum HeatPumpState {
    NORMALOPERATION,        // Normalbetrieb
    SUSPENDED,              // Sperre
    PRECEDENCE,             // Vorzugsbetrieb
    ENFORCED                // Abnahmezwang
}
