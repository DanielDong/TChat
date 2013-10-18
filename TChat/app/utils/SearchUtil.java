package utils;

import java.util.ArrayList;

public class SearchUtil {
	/**
	 * Search pattern string in the target string.
	 * @param target The chat room history.
	 * @param pattern The sub-string to be searched in the chat history.
	 * @return formatted chat history with matches highlighted and the nubmer of matches.
	 */
	public static ArrayList<String> searchChatHistory(String target, String pattern){
		BoyerMoore bm = new BoyerMoore(target, pattern);
		ArrayList<Integer> indexList = bm.bm();
		ArrayList<String> retList = new ArrayList<String>();
		// If only one element in indexList, no match.
		if(indexList.size() == 1){
			// Final processed chat history.
			retList.add(target);
			// Number of found matches.
			retList.add("0");
			return retList;
		}else{
			String leftTag = "<span style='background-color:yellow;'>";
			String rightTag = "</span>";
			StringBuilder sb = new StringBuilder(target);
			// 39 7
			int leftLen = leftTag.length(), rightLen = rightTag.length();
			// 0 1 2 3 4 5
			for(int i = 0; i < indexList.size() - 1; i ++){
				// Add left span tag.
				if(i % 2 == 0){
					int realLeftIndex = indexList.get(i) + (i / 2) * (leftLen + rightLen);
					sb.insert(realLeftIndex, leftTag);
				}
				// Add right span tag.
				else{
					int realRightIndex = indexList.get(i) +  (i / 2 ) * (leftLen + rightLen) + leftLen + 1;
					sb.insert(realRightIndex, rightTag);
				}
			}
			// Final processed chat history.
			retList.add(sb.toString());
			// Number of found matches.
			retList.add(indexList.get(indexList.size() - 1).toString());
			return retList;
		}
	}
	
//	public static void main(String[] args){
//		String target = "hello, backstreet boys comback again! backstreet";
//		String pattern = "backstreet";
////		target = "xxxbacdvvvbacdbb";
////		pattern = "bacd";
//		ArrayList<String> retList = searchChatHistory(target, pattern);
//		BoyerMoore bm = new BoyerMoore(target, pattern);
//		ArrayList<Integer>  retIList = new ArrayList<Integer> ();
//		retIList = bm.bm();
//		for(int i = 0; i < retList.size(); i ++)
//			System.out.print(retList.get(i) + " ");
//		System.out.println();
//		for(int i = 0; i < retIList.size(); i ++)
//			System.out.print(retIList.get(i) + " ");
//		System.out.println();
//	}
	
	/**
	 * Boyer Moore String Searching algorithm similar to KMP. But it is more efficient than KMP.
	 * 
	 * @author shichaodong
	 * @version 1.0
	 */
	public static class BoyerMoore {

		private String pattern;
		private String target;
		
		private int[][] badCharOffset;
		private int[] goodSuffixOffset;
		
		public BoyerMoore(){}
		public BoyerMoore(String tar, String pat){
			pattern = pat;
			target = tar;
			
			// Initialize the bad charater table.
			badCharOffset = new int[128][pattern.length()];
			for(int i = pattern.length() - 1; i >= 0; i --){
				for(int j = 0; j < 128; j ++){
					if(j != pattern.charAt(i)){
						int lastIndex = pattern.lastIndexOf(j, i - 1);
						badCharOffset[j][i] = i - lastIndex;
					}else{
						badCharOffset[j][i] = 0;
					}
				}
			}
			// Initialize the good suffix table.
			goodSuffixOffset = new int[pattern.length()];
			for(int index = pattern.length() - 1; index > 0; index --){
				int offset = 0;
				String goodSuffix = pattern.substring(index + 1);
				if(goodSuffix != ""){
					int tmpOffset = pattern.lastIndexOf(goodSuffix, pattern.length() - 1);
					// NO good suffix in the pattern.
					if(tmpOffset == -1){
						for(int i = index + 2; i < pattern.length(); i ++){
							String tmpSubGoodSuffix = pattern.substring(i);
							int tmpSubGoodSuffixOffset = pattern.indexOf(tmpSubGoodSuffix);
							if(tmpSubGoodSuffixOffset == 0){
								offset = i;
								break;
							}
						}
						if(offset == 0)
							offset = pattern.length();
					}else{
						offset = index + 1 - tmpOffset;
					}
				}
				goodSuffixOffset[index] = offset;
			}
		}// end constructor
		
		// Bad character shifts.
		// Character at index is the character which has not a match.
		private int getBadCharOffsetByArray(char c, int index){
			return badCharOffset[c][index];
		}
		// Good suffix shifts
		private int getGoodSuffixOffsetByArray(int index){
			return goodSuffixOffset[index];
		}
		/**
		 * Boyer Moore algorithm to search pattern from target.
		 * @return
		 */
		public ArrayList<Integer> bm(){
			int it = pattern.length() - 1, ip = pattern.length() - 1, offset = 0;
			// The number of matches found.
			int cnt = 0;
			// Used to store found start and end of each match.
			ArrayList<Integer> indexList = new ArrayList<Integer>();
			int end = 0, start = 0;
			while(true){
				it += offset;
				end = it;
				if(it >= target.length())
					break;
				for(; it >= 0 && ip >= 0 && target.charAt(it) == pattern.charAt(ip); it --, ip --);
				
				// One match is found.
				if(ip == -1){
					ip = pattern.length() - 1;
					offset = 2 * pattern.length();
					cnt ++;
					start = it + 1;
					// Add start and end of one match.
					indexList.add(start);
					indexList.add(end);
				}
				// One character mismatch occurs.
				else{
					
					offset = Math.max(getBadCharOffsetByArray(target.charAt(it), ip), getGoodSuffixOffsetByArray(ip));
					it += pattern.length() - 1 - ip;
					ip = pattern.length() - 1;
				}
			}
			// The last element stores the number of matches found.
			indexList.add(cnt);
			return indexList;
		}
	}// end Boyer Moore algorithm
}
