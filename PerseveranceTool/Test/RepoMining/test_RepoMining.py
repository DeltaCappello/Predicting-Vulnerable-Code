import unittest
import os
from unittest.mock import MagicMock
from pydriller import RepositoryMining

from ...Application.RepoMining.RepoMining import RepoMining

class Test_RepoMining(unittest.TestCase):
    repo_url = "https://github.com/DeltaCappello/Predicting-Vulnerable-Code"
    commitHash = "912fa87667aa3fbaa68c31913790f2ac758ab368"


    #----------------------isEmptyUrl(self, repo_url)-------------------------------------------
    """Test if the passed URL is valid"""
    def test_isEmptyUrl_Valid(self):
        result = RepoMining.isEmptyUrl(self, self.repo_url)
        self.assertFalse(result, "https://github.com/DeltaCappello/Predicting-Vulnerable-Code")

    """Test if the passed URL is None"""
    def test_isEmptyUrl_None(self):
        result = RepoMining.isEmptyUrl(self, None)
        self.assertTrue(result, None)

    """Test if the URL is empty"""
    def test_isEmpty_empty(self):
        result = RepoMining.isEmptyUrl(self, "")
        self.assertTrue(result, "")

    #----------------------isEmptyHash(self, commitHash)-------------------------------------------

    """Test if the hash of the commit is Valid"""
    def test_isEmptyHash(self):
        result = RepoMining.isEmptyHash(self, self.commitHash)
        self.assertFalse(result, "912fa87667aa3fbaa68c31913790f2ac758ab368")

    """Test if the hash of the commit is None"""
    def test_isEmptyHash(self):
        result = RepoMining.isEmptyHash(self, None)
        self.assertTrue(result, None)

    """Test if the hash of the commit is empty"""
    def test_isEmptyHash(self):
        result = RepoMining.isEmptyHash(self, "")
        self.assertTrue(result, "")

    #----------------------setDefaultPath(self)-------------------------------------------
    """Test if the default Directory is right for the system that we are using"""
    def test_setDefaultPath(self):
        result = RepoMining.setDefaultPath(self)
        self.assertEqual(result, "/Users/UniSa/Desktop/")

    #----------------------getAllCommit(self, repo_url)-------------------------------------------
    """Test if the commit of a specific repository are valid"""
    def test_getAllCommit(self):
        gotAllCommit = []
        result = RepoMining.getAllCommit(RepoMining, "https://github.com/Dariucc07/Sometest")
        for commit in RepositoryMining("https://github.com/Dariucc07/Sometest").traverse_commits():
            gotAllCommit.append(commit.hash)
        self.assertListEqual(result, gotAllCommit)

    # ----------------------setDefaultDir(self)-------------------------------------------
    """Test the Default directory in which the mining will start. The folder will be in ./Desktop of the users"""
    def test_setDefaultDir(self):
        cwd_oracle = "/Users/UniSa/Desktop/Perseverance"
        result = RepoMining.setDefaultDir(RepoMining)
        self.assertEqual(result, cwd_oracle)

    # ----------------------startMining(self, repo_url, commitHash)-------------------------------------------
    """Test that all the file of a specific commithash has the right element inside the folder"""
    def test_startMining(self):
        allfile = []
        fileOracle = ["Rational.java"]
        RepoMining.startMining(RepoMining, "https://github.com/Dariucc07/Sometest", "6edb52c9639364b67f8ad89f2ca079e7552bd469") #the hash commit is for a commit that i know that have a modification at least of one file
        os.chdir("/Users/UniSa/Desktop/Perseverance/6edb52c9639364b67f8ad89f2ca079e7552bd469")
        allfile = os.listdir()
        self.assertListEqual(allfile, fileOracle)

    # ----------------------exist(self, repo_url, commitHash)-------------------------------------------
    def test_existWithoutCommitHash(self):
        oracle = []
        for commit in RepositoryMining("https://github.com/Dariucc07/Sometest").traverse_commits():
            oracle.append(commit.hash)
        result = RepoMining.exist(RepoMining, "https://github.com/Dariucc07/Sometest", None)
        self.assertListEqual(result, oracle)

    def test_existWithEmptyCommitHash(self):
        oracle = []
        for commit in RepositoryMining("https://github.com/Dariucc07/Sometest").traverse_commits():
            oracle.append(commit.hash)
        result = RepoMining.exist(RepoMining, "https://github.com/Dariucc07/Sometest", "")
        self.assertListEqual(result, oracle)

    def test_existWithEmptyRepoUrlOrNone(self):
        result = RepoMining.exist(RepoMining, None or "", "")
        self.assertIsNone(result)

    def test_existWithRepoUrlAndCommitHashValid(self):
        oracle = ["Rational.java"]
        result = RepoMining.exist(RepoMining, "https://github.com/Dariucc07/Sometest", "6edb52c9639364b67f8ad89f2ca079e7552bd469")
        print(result)
        self.assertListEqual(result, oracle)
