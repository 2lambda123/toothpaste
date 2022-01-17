module TPConform where

import ProbProcessTree
import TraceUtil


-- convenience
log2 :: Floating a => a -> a
log2 = logBase 2 

-- permutation utilities

-- each element in the list, paired with the remainder of the list
elemCompl :: [a] -> [(a,[a])]
elemCompl (x:xs) = elemCompl2 [] x xs
elemCompl [] = []

elemCompl2 :: [a] -> a -> [a] -> [(a,[a])]
elemCompl2 xs y (z:zs) = (y,xs++(z:zs)) : elemCompl2 (xs ++ [y]) z zs
elemCompl2 xs y [] = [(y,xs)]

headify :: a -> [[a]] -> [[a]]
headify h = map (h:) 

permute :: [a] -> [[a]]
permute (x:xs) = concatMap (\(y,yl) -> headify y (permute yl)  ) 
                              (elemCompl (x:xs))
permute []     = [[]]


loud :: PPTree a -> Bool
loud (NodeN op ptl w) = all (loud) ptl
loud (Node1 FLoop pt r w) = loud pt
loud (Node1 PLoop pt r w) = False
loud (Leaf x w) = True
loud (Silent w) = False

-- default epsilon for approximations
defaulteps = 0.001

-- probability [0,1]
prob :: (Eq a, Ord a) => [a] -> PPTree a -> Float
prob s (NodeN Choice ptl w) =  sum (map (\u -> weight u * prob s u) ptl) / wt
    where wt = sum (map weight ptl)
prob s (Leaf x w) | s == [x]    = 1
                  | otherwise = 0
prob s (Silent w) | null s    = 1
                  | otherwise = 0
prob s (NodeN Seq  ptl w) = probSeq s ptl
prob s (Node1 FLoop pt r w) 
    = prob s (NodeN Seq (duplicate [pt] (round r)) (weight pt) ) 
prob s (NodeN Conc ptl w) = 0 -- probConc s ptl TODO BROKEN
prob s (Node1 PLoop pt r w) = probPLoop s pt r defaulteps

-- probConcRegion :: (Eq a, Ord a) => [a] -> PPTree a -> Float
-- TODO rest
-- probConcRegion s (Leaf x w) = prob s (Leaf x w)
-- probConcRegion s (Silent w) = prob s (Silent w)




probConc :: (Eq a, Ord a) => [a] -> [PPTree a] -> Float
probConc s [pt] = prob s pt
-- probConc s (pt:ptl) =   probConcC s 1 (pt:ptl)
--                       + probConcSplits [] s (pt:ptl) 
probConc s (pt:ptl)
        = sum( map (\ss -> probConcC ss 1 (pt:ptl)
                         + probConcSplits [] ss (pt:ptl) )
                   pms ) / (fromIntegral $ length pms)
          where pms = permute s

probConcSplits :: (Eq a, Ord a) => [a] -> [a] -> [PPTree a] -> Float
probConcSplits s1 s2 ptl = sum( map (\(u,uptl) -> (weight u)
                                       * prob s1 u
                                       * probConc s2 uptl )
                                (elemCompl ptl) )
                           / wt
               where wt = sum (map weight ptl) 

probConcC :: (Eq a, Ord a) => [a] -> Int -> [PPTree a] -> Float
probConcC s n (pt:ptl) 
    | n <  length s =   probConcSplits fs sn (pt:ptl)  
                      + probConcC s (n+1) (pt:ptl)
    | n == length s = probConcSplits s  [] (pt:ptl) 
     where  (fs,sn) = splitAt n s
probConcC s n ptl = 0 

probSeq :: (Eq a, Ord a) => [a] -> [PPTree a] -> Float
probSeq s [pt]     = prob s pt
probSeq s (pt:ptl) = prob [] pt * probSeq s ptl
                   + probSeqS s 1 (pt:ptl)
    where w = weight pt
probSeq [] [] = warn "Empty in probSeq (empty trace) "  1
probSeq s  [] = warn "Empty in probSeq "  0

probSeqS :: (Eq a, Ord a) => [a] -> Int -> [PPTree a] -> Float
probSeqS s n (pt:ptl) 
    | n < length s  = prob fs pt * prob sn (NodeN Seq ptl w)
                      + probSeqS s (n+1) (pt:ptl)
    | n == length s = prob s pt * prob [] (NodeN Seq ptl w)
        where (fs,sn) = splitAt n s
              w       = weight pt
probSeqS s n ptl      = 0

-- probPLoop is an approximation for silent subtrees
-- sigma subtree reps epsilon
probPLoop :: (Eq a, Ord a) => [a] -> PPTree a -> Float -> Float -> Float
probPLoop [] pt r eps = (1/r) + (prob [] pt) / r**2
probPLoop s pt r eps | length s == 1 = prob s pt *((r-1)/r^2) 
                                     + prob s pt * (prob [] pt) / r**2  
                     | loud (pt) = (probLoudLoop s pt r) /r 
                     | otherwise = probPLoopApprox s pt r eps


probLoudLoop :: (Eq a, Ord a) => [a] -> PPTree a -> Float -> Float 
probLoudLoop s pt r = probPLoopNth s pt r 1 (length s)
                        
probPLoopApprox :: (Eq a, Ord a) => [a] -> PPTree a -> Float -> Float -> Float
probPLoopApprox s pt r eps = (probPLoopNth s pt r 1 k)/r
            where k = findLoopApproxK r eps

probPLoopNth ::  (Eq a, Ord a) => [a] -> PPTree a -> Float -> Int -> Int 
                                      -> Float 
probPLoopNth s pt r i n | i < n  = pli + probPLoopNth s pt r (i+1) n
                        | i >= n = pli 
            where pli = ((r-1)/r)^i * (prob s (Node1 FLoop pt (fromIntegral i) 
                                                               (weight pt)) )
 
findLoopApproxK :: Float -> Float -> Int
findLoopApproxK r eps = findLoopApproxKAccum r eps (r-1) 1

findLoopApproxKAccum :: Float -> Float -> Float -> Int -> Int
findLoopApproxKAccum r eps cum i 
    | nc  <  eps = i
    | nc  >= eps = findLoopApproxKAccum r eps nc (i+1)
    where nc = cum*(r-1)/r 




