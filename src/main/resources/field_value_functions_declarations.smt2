; This Source Code Form is subject to the terms of the Mozilla Public
; License, v. 2.0. If a copy of the MPL was not distributed with this
; file, You can obtain one at http://mozilla.org/MPL/2.0/.

; The definitions are parametric
;   - $FLD$ is a Silver field name
;   - $S$ is the sort corresponding to the type of the field
;   - $T$ is the sanitized name of the sort corresponding to the type of the field

(declare-fun $FVF.perm_$FLD$ ($FPM $Ref) $Perm)

(declare-fun $FVF.domain_$FLD$ ($FVF<$FLD$>) (Array $Ref Bool))
(declare-fun $FVF.array_$FLD$ ($FVF<$FLD$>) (Array $Ref $S$))
(declare-fun $FVF.defn_$FLD$ ((Array $Ref $S$) (Array $Ref Bool)) $FVF<$FLD$>)
(declare-const $FVF.undefined_$FLD$ $S$)

(declare-fun $FVF.loc_$FLD$ ($S$ $Ref) Bool)