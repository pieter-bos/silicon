; This Source Code Form is subject to the terms of the Mozilla Public
; License, v. 2.0. If a copy of the MPL was not distributed with this
; file, You can obtain one at http://mozilla.org/MPL/2.0/.

; The axioms are parametric
;   - $FLD$ is a Silver field name
;   - $S$ is the sort corresponding to the type of the field
;   - $T$ is the sanitized name of the sort corresponding to the type of the field

; ATTENTION: The triggers mention the sort wrappers introduced for FVFs.
; The axiom therefore needs to be emitted after the sort wrappers have
; been emitted.

(assert (forall ((vs $FVF<$FLD$>)) (!
  (forall ((x $Ref))
    (=
      (select ($FVF.lookup_$FLD$_arr vs) x)
      (ite (Set_in x ($FVF.domain_$FLD$ vs))
        ($FVF.lookup_$FLD$ vs x)
        $FVF.lookup_dummy_$FLD$
      )
    )
  )
  :pattern (($SortWrappers.$FVF<$FLD$>To$Snap vs))
  :qid |fvfDefLookupArr$FLD$|
  )))

(assert (forall ((vs $FVF<$FLD$>)) (!
  (forall ((x $Ref))
    (=
      (select ($FVF.domain_$FLD$_arr vs) x)
      (Set_in x ($FVF.domain_$FLD$ vs))
    )
  )
  :pattern (($SortWrappers.$FVF<$FLD$>To$Snap vs))
  :qid |fvfDefDomainArr$FLD$|
  )))

(assert (forall ((vs $FVF<$FLD$>)) (!
  (=
    vs
    ($FVF.mk_for_eq_$FLD$
      ($FVF.domain_$FLD$_arr vs)
      ($FVF.lookup_$FLD$_arr vs)
    )
  )
  :pattern (($SortWrappers.$FVF<$FLD$>To$Snap vs))
  :qid |fvfEqDef$FLD$|
  )))

(assert (forall ((r $Ref) (pm $FPM)) (!
    ($Perm.isValidVar ($FVF.perm_$FLD$ pm r))
    :pattern (($FVF.perm_$FLD$ pm r)))))

(assert (forall ((r $Ref) (f $S$)) (!
    (= ($FVF.loc_$FLD$ f r) true)
    :pattern (($FVF.loc_$FLD$ f r)))))
