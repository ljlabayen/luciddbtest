/*
// $Id$
// Package org.eigenbase is a class library of data management components.
// Copyright (C) 2005-2005 The Eigenbase Project
// Copyright (C) 2005-2005 Disruptive Tech
// Copyright (C) 2005-2005 LucidEra, Inc.
//
// This program is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation; either version 2 of the License, or (at your option)
// any later version approved by The Eigenbase Project.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package org.eigenbase.sql.type;

import org.eigenbase.reltype.*;
import org.eigenbase.util.*;
import org.eigenbase.sql.*;
import org.eigenbase.sql.validate.*;

/**
 * Returns the first type that matches a set of given {@link SqlTypeName}s.
 * If no match could be found, null is returned.
 *
 * @author Wael Chatila
 * @version $Id$
 */
public class MatchReturnTypeInference
    implements SqlReturnTypeInference
{
    private final int start;
    private final SqlTypeName[] typeNames;

    /**
     * Returns the type at element start (zero based)
     * @see {@link TypeMatchReturnTypeInference(int, SqlTypeName[])}
     */
    public MatchReturnTypeInference(int start) {
        this(start, SqlTypeName.Any);
    }

    /**
     * Returns the first type of typeName at or after position start
     * (zero based)
     * @see {@link TypeMatchReturnTypeInference(int, SqlTypeName[])}
     */
    public MatchReturnTypeInference(int start, SqlTypeName typeName) {
        this(start, new SqlTypeName[]{typeName});
    }

    /**
     * Returns the first type matching any type in typeNames at or after
     * position start (zero based)
     * @pre start>=0
     * @pre null!=typeNames
     * @pre typeNames.length>0
     */
    public MatchReturnTypeInference(int start, SqlTypeName[] typeNames)
    {
        Util.pre(start>=0,"start>=0");
        Util.pre(null!=typeNames,"null!=typeNames");
        Util.pre(typeNames.length>0,"typeNames.length>0");
        this.start = start;
        this.typeNames = typeNames;
    }

    public RelDataType getType(
        SqlValidator validator,
        SqlValidatorScope scope,
        RelDataTypeFactory typeFactory,
        CallOperands callOperands) {
        for (int i = start; i < callOperands.size(); i++) {
            RelDataType argType = callOperands.getType(i);
            if (SqlTypeUtil.isOfSameTypeName(typeNames, argType)) {
                return argType;
            }
        }
        return null;
    }
}

// End MatchReturnTypeInference.java