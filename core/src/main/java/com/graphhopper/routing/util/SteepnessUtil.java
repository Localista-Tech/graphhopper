package com.graphhopper.routing.util;

import java.util.List;

import com.graphhopper.util.DistanceCalc;
import com.graphhopper.util.PointList;

public class SteepnessUtil {

	public static double ELEVATION_THRESHOLD = 20;
	
	public static int getCategory(double value)
	{
		if (Double.isNaN(value))
			return 0;
		
		double absValue = Math.abs(value);
		int res = 0;
		
		// 0%: A flat road
		if (absValue < 1.0)
			res = 0;
		// 1-3%: Slightly uphill but not particularly challenging. A bit like riding into the wind.
		else if (absValue >=1 && absValue < 4)
			res = 1;
		// 4-6%: A manageable gradient that can cause fatigue over long periods.
		else if (absValue >= 4 && absValue < 7)
			res = 2;
		// 7-9%: Starting to become uncomfortable for seasoned riders, and very challenging for new climbers.
		else if (absValue >= 7 && absValue < 10)
			res = 3;
		// 10%-15%: A painful gradient, especially if maintained for any length of time
		else if (absValue >= 10 && absValue < 16)
			res = 4;
		// 16%+: Very challenging for riders of all abilities. Maintaining this sort of incline for any length of time is very painful.
		else if (absValue >= 16)
			res = 5;
		
		return res*((value > 0) ? 1 :-1);
	}
	
	public static void computeRouteSplits(PointList points, boolean reverse, DistanceCalc dc, List<RouteSplit> splits)
	{
		splits.clear();
		
		if (points.size() == 0)
			return;

		int nPoints = points.size();
		int i0 = reverse ? nPoints - 1 :0;
		double maxAltitude = Double.MIN_VALUE;
		double minAltitude = Double.MAX_VALUE;
		double prevMinAltitude, prevMaxAltitude;
		int iStart = i0;
		double splitLength = 0;
		double cumElev = 0.0;
		RouteSplit prevSplit = null;
		int prevGC = 0;
		int iPoints = 0;

		double x0 = points.getLon(i0);
		double y0 = points.getLat(i0);
		double z0 = points.getEle(i0);
		
		if (z0 > maxAltitude)
			maxAltitude = z0;
		if (z0 < minAltitude)
			minAltitude = z0;
	
		for (int j = 1; j < nPoints; j++) {
			
			int jj = reverse ? (nPoints - 1 - j) : j;
			double x1 = points.getLon(jj);
			double y1 = points.getLat(jj);
			double z1 = points.getEle(jj);
			
			double elevDiff = z1 - z0;
			double length = dc.calcDist(y0, x0, y1, x1);
			cumElev += elevDiff;
			
			splitLength += length;
			
			prevMinAltitude = minAltitude;
			prevMaxAltitude = maxAltitude;
			if (z1 > maxAltitude)
				maxAltitude = z1;
			if (z1 < minAltitude)
				minAltitude = z1;
			 
			if (maxAltitude - z1 > ELEVATION_THRESHOLD || z1 - minAltitude > ELEVATION_THRESHOLD)
			{
				boolean bApply = true;
				int elevSign = cumElev > 0 ? 1 : -1;
				double gradient = elevSign*100*(prevMaxAltitude - prevMinAltitude) / splitLength;
				if (Double.isNaN(gradient) || Math.abs(gradient) > 30) // possibly noise
					gradient = 0.0;
				
				if (prevGC != 0 )
				{
					double zn= Double.MIN_NORMAL;
					
					if (!reverse)
					{
						if (jj + 1 < nPoints)
							zn = points.getEle(jj + 1);
					}
					else
					{
						if (jj - 1 >= 0)
							zn = points.getEle(jj - 1);
					}

					if (zn != Double.MIN_VALUE)
					{						
						double elevGap = length/30;
						if (elevSign > 0 /* && Math.Abs(prevSplit.Gradient - gradient) < gradientDiff)//*/ && prevGC > 0)
						{
							if (Math.abs(zn - z1) < elevGap)
								bApply = false;
						}
						else if(/*Math.Abs(prevSplit.Gradient - gradient) < gradientDiff)//*/prevGC < 0)
						{
							if (Math.abs(zn - z1) < elevGap)
								bApply = false;
						}
					}
				}
				
				if (bApply)
				{
					int iEnd = reverse ? (nPoints - iPoints - 1) : (iPoints - 1);
					
					int gc = getCategory(gradient);
					RouteSplit split = new RouteSplit();
					split.Value = gc;
					split.Gradient = gradient;
					split.Length = splitLength;
					split.VerticalClimb = prevMaxAltitude - prevMinAltitude;

					if (reverse)
					{
						split.Start = iEnd;
						split.End = iStart;
						splits.add(0,split);
					}
					else
					{
						split.Start = iStart;
						split.End = iEnd;
						splits.add(split);
					}

					prevGC = gc;
					prevSplit = split;
					
					iStart = iEnd;
					minAltitude = Math.min(z0, z1);
					maxAltitude = Math.max(z0, z1);
					splitLength = 0.0;
					
					cumElev= elevDiff;
				}
			}
			
			x0 = x1;
			y0 = y1;
			z0 = z1;
			iPoints++;
		}
		
		if (splitLength > 0)
		{
			int iEnd = reverse ? (nPoints - iPoints - 1) : (iPoints - 1);
			double elevDiff = maxAltitude - minAltitude;
			if (splits.size() == 0 && splitLength < 50 && elevDiff < ELEVATION_THRESHOLD)
				elevDiff = 0;
			double gradient = (cumElev > 0 ? 1: -1)*100*elevDiff / splitLength;
			if (Math.abs(gradient) > 7 && maxAltitude < 100 && splitLength < 120)
				gradient = 0.0; //  noise
				
			int gc = getCategory(gradient);
			if (prevSplit != null && (prevSplit.Value == gc || splitLength < 25))
			{
				prevSplit.End = iEnd;
			}
			else
			{
				RouteSplit lastSplit = new RouteSplit();
				lastSplit.Value = gc;
				lastSplit.Gradient = gradient;
				lastSplit.Length = splitLength;
				lastSplit.VerticalClimb = elevDiff;

				if (reverse)
				{
					lastSplit.Start = iEnd;
					lastSplit.End = iStart;
					splits.add(0,lastSplit);
				}
				else
				{
					lastSplit.Start = iStart;
					lastSplit.End = iEnd;
					splits.add(lastSplit);
				}
			}
		}
	}
}